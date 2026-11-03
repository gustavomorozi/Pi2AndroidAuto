package com.morozi.autophone

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

private const val TAG = "AapSession"

/** Control channel carries the version handshake, then TLS, then service discovery. */
private const val CONTROL_CHANNEL = 0

/**
 * Message IDs on the control channel. Values below are what we observed on the
 * wire testing against our own Open Headunit instance (see
 * MVP-raspberry-android-tv-carplay.md) -- not copied from any product's source,
 * just read off a live, working session we captured ourselves.
 */
private object ControlMessage {
    const val VERSION_REQUEST = 0x0001
    const val VERSION_RESPONSE = 0x0002
    const val SSL_HANDSHAKE = 0x0003
    const val AUTH_COMPLETE = 0x0004
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006 // confirmed: the car's reply
    // Below: not yet seen on the wire; the car's reaction will confirm them.
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000b
    const val PING_RESPONSE = 0x000c
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013
}

/** Message ids on the input channel. Not yet seen on the wire. */
private object InputMessage {
    const val INPUT_EVENT = 0x8001
    const val KEY_BINDING_REQUEST = 0x8002
    const val KEY_BINDING_RESPONSE = 0x8003
}

/** Message ids on a media channel (video here). Not yet seen on the wire. */
private object MediaMessage {
    const val DATA = 0x0000 // 8-byte timestamp (us) + one encoded frame
    const val CODEC_CONFIG = 0x0001 // SPS/PPS, no timestamp
    const val SETUP = 0x8000
    const val START = 0x8001
    const val CONFIG = 0x8003
    const val ACK = 0x8004
    const val VIDEO_FOCUS_INDICATION = 0x8008
}

private const val VERSION_STATUS_SUCCESS = 0x0000

private const val VIDEO_FOCUS_PROJECTED = 1L
private const val AUDIO_FOCUS_GAIN = 1L
private const val TOUCH_ACTION_DOWN = 0L

/** Largest frame the Pi's software H.264 encoder keeps up with (1280x720). */
private const val MAX_ENCODE_PIXELS = 1280 * 720

/** How long a media send waits for a free slot before going ahead anyway. */
private const val ACK_WAIT_SECONDS = 2L

/** PCM per audio message: ~42 ms of 48 kHz stereo 16-bit. */
private const val AUDIO_CHUNK_BYTES = 8192

/** Keeps each frame's TLS record under the 16 KiB record limit. */
private const val MAX_FRAME_PLAINTEXT = 16000

/**
 * Drives one Android Auto session over an already-open USB accessory stream
 * pair, playing the "phone" role: the car sends the version request and opens
 * TLS as the client; we answer the version request and are the TLS server.
 *
 * Current state, tested against our own Open Headunit over Wi-Fi: version,
 * TLS, AUTH_COMPLETE, service discovery and our generated video all work.
 * Touch input, the audio beep and ACK-based flow control are new.
 */
class AapSession(
    rawInput: InputStream,
    rawOutput: OutputStream,
    private val onEnded: (reason: String) -> Unit
) {
    private val reader = AapFrameReader(rawInput)
    private val writer = AapFrameWriter(rawOutput)

    @Volatile private var running = true

    private var videoStream: MediaStream? = null
    private var audioStream: MediaStream? = null
    private var audioSampleRate = 48000
    private var inputChannel = -1
    private var videoSource: VideoSource? = null
    private var videoConfig: VideoConfig? = null

    /**
     * One service from the car's discovery reply. Shapes read off the capture:
     * field 3 = media sink (field 1 codec, field 3 audio config {rate, bits,
     * channels}, field 4 video configs), field 4 = input (keycodes in field 1).
     */
    private class Service(val channel: Int, val proto: ProtoReader) {
        private val sink = proto.messages(3).firstOrNull()
        val codec: Long = sink?.varints(1)?.firstOrNull() ?: 0
        val isVideo = sink?.messages(4)?.isNotEmpty() == true
        val isInput = proto.messages(4).isNotEmpty()
        val audioSampleRate: Int = sink?.messages(3)?.firstOrNull()?.varints(1)?.firstOrNull()?.toInt() ?: 0
        val audioChannels: Int = sink?.messages(3)?.firstOrNull()?.varints(3)?.firstOrNull()?.toInt() ?: 0
        // Music/media sink: the stereo 48 kHz one in the capture.
        val isMediaAudio = !isVideo && audioChannels == 2
        val keycodes: List<Long> = proto.messages(4).firstOrNull()?.varints(1) ?: emptyList()
        /** The car's video configurations, in the order it listed them (its preference). */
        val videoConfigs: List<VideoConfig> = sink?.messages(4)?.map { VideoConfig.from(it) } ?: emptyList()
    }

    /**
     * One video configuration offered by the car (captured fields: 1 resolution
     * code, 2 frame-rate code, 3/4 width/height margins, 5 density). The margins
     * are how the car fits a standard video size onto a screen of a different
     * shape: it crops them off, so content must stay inside the remaining area.
     */
    private data class VideoConfig(
        val width: Int, val height: Int, val fps: Int,
        val marginWidth: Int, val marginHeight: Int, val density: Int
    ) {
        companion object {
            fun from(p: ProtoReader): VideoConfig {
                val (w, h) = when (p.varints(1).firstOrNull()?.toInt()) {
                    1 -> 800 to 480
                    3 -> 1920 to 1080
                    4 -> 2560 to 1440
                    5 -> 3840 to 2160
                    else -> 1280 to 720
                }
                val fps = if (p.varints(2).firstOrNull()?.toInt() == 1) 60 else 30
                return VideoConfig(
                    w, h, fps,
                    marginWidth = p.varints(3).firstOrNull()?.toInt() ?: 0,
                    marginHeight = p.varints(4).firstOrNull()?.toInt() ?: 0,
                    density = p.varints(5).firstOrNull()?.toInt() ?: 160
                )
            }
        }
    }

    /**
     * Picks the car's most preferred configuration our encoder can keep up with.
     * The Pi 5 encodes H.264 in software (~20 fps at 1280x720 measured), so
     * anything above 720p is skipped, and 60 fps is capped to 30.
     */
    private fun chooseVideoConfig(configs: List<VideoConfig>): Pair<Int, VideoConfig> {
        val index = configs.indexOfFirst { it.width * it.height <= MAX_ENCODE_PIXELS }.takeIf { it >= 0 } ?: 0
        val chosen = configs.getOrElse(index) { VideoConfig(1280, 720, 30, 0, 0, 160) }
        return index to chosen.copy(fps = minOf(chosen.fps, 30))
    }

    /**
     * A media channel we send on. The car's CONFIG says how many messages may be
     * outstanding without an ACK (captured: 12 for video); [send] blocks once
     * that many are in flight and each ACK frees slots again.
     */
    private inner class MediaStream(val channel: Int, maxUnacked: Int, private val tls: TlsChannel) {
        private val window = java.util.concurrent.Semaphore(maxUnacked)
        private val loggedAck = java.util.concurrent.atomic.AtomicBoolean(false)

        fun send(id: Int, body: ByteArray) {
            if (!window.tryAcquire(ACK_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "No ACK on channel $channel for ${ACK_WAIT_SECONDS}s; sending anyway")
            }
            sendMessage(channel, id, body, tls)
        }

        fun onAck(body: ByteArray) {
            // ACK body: field 1 session, field 2 how many messages it acknowledges.
            val count = ProtoReader(body).varints(2).firstOrNull()?.toInt() ?: 1
            if (loggedAck.compareAndSet(false, true)) Log.i(TAG, "First ACK on channel $channel: ${body.toHex()}")
            window.release(count)
        }
    }

    fun run() {
        try {
            performVersionHandshake()
            val tls = performTlsHandshake()

            readAuthComplete()
            sendServiceDiscoveryRequest(tls)
            val services = readServiceDiscoveryResponse(tls)

            val video = services.first { it.isVideo }
            val (configIndex, config) = chooseVideoConfig(video.videoConfigs)
            videoConfig = config
            Log.i(TAG, "Video config #$configIndex of ${video.videoConfigs.size}: $config")
            openChannel(video.channel, tls)
            videoStream = setUpMedia(video, tls, configIndex)
            sendMessage(
                video.channel,
                MediaMessage.VIDEO_FOCUS_INDICATION,
                ProtoWriter().varint(1, VIDEO_FOCUS_PROJECTED).varint(2, 0).toByteArray(),
                tls
            )

            services.firstOrNull { it.isInput }?.let { input ->
                openChannel(input.channel, tls)
                bindKeys(input, tls)
                inputChannel = input.channel
            }
            services.firstOrNull { it.isMediaAudio }?.let { audio ->
                openChannel(audio.channel, tls)
                requestAudioFocus(tls)
                audioStream = setUpMedia(audio, tls)
                audioSampleRate = audio.audioSampleRate
            }

            streamVideo(tls)
            onEnded("video stream ended")
        } catch (t: Throwable) {
            Log.e(TAG, "Session failed", t)
            onEnded("error: ${t.message}")
        }
    }

    /**
     * We are the phone: the CAR sends VERSION_REQUEST first and waits for our
     * VERSION_RESPONSE -- confirmed empirically (our first attempt had both
     * sides write a request and deadlock waiting for a response; Open Headunit's
     * own log on that attempt said it "accepted the connection and then sent
     * nothing", i.e. it was the one waiting on *us*). We never write first here.
     */
    private fun performVersionHandshake() {
        val frame = reader.readFrame()
        require(frame.channelId == CONTROL_CHANNEL) { "Expected control channel, got ${frame.channelId}" }
        Log.i(TAG, "First control frame: flags=0x${frame.flags.toString(16)} bytes=${frame.payload.toHex()}")

        val buf = ByteBuffer.wrap(frame.payload)
        val requestId = buf.short.toInt() and 0xFFFF
        val major = buf.short
        val minor = buf.short
        Log.i(TAG, "Read as VERSION_REQUEST: messageId=$requestId major=$major minor=$minor")

        // Response id: unconfirmed guess (request id + 1, a common pairing
        // convention) -- the next test run's log will tell us if this is wrong,
        // same way the request's real id (3, not our earlier guess of 1) did.
        val responseId = requestId + 1
        val response = ByteBuffer.allocate(8)
            .putShort(responseId.toShort())
            .putShort(major)
            .putShort(minor)
            .putShort(VERSION_STATUS_SUCCESS.toShort())
            .array()
        writer.writeFrame(AapFrame(CONTROL_CHANNEL, AapFrame.FLAG_UNFRAGMENTED, response))
        Log.i(TAG, "Sent VERSION_RESPONSE (messageId=$responseId) echoing $major.$minor, status=SUCCESS")
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    /**
     * Captured: right after TLS the car sends, in plain text on the control
     * channel, `00 04 08 00` -- message id 4 with a protobuf status field of 0.
     */
    private fun readAuthComplete() {
        val frame = reader.readFrame()
        val messageId = frame.messageId()
        require(frame.channelId == CONTROL_CHANNEL && messageId == ControlMessage.AUTH_COMPLETE) {
            "Expected AUTH_COMPLETE, got channel ${frame.channelId} id $messageId"
        }
        Log.i(TAG, "AUTH_COMPLETE received: ${frame.payload.toHex()}")
    }

    /**
     * The phone asks the car which services it offers. Body is a protobuf with
     * our display name (field 4) and brand (field 5); the icon fields are left
     * out. Field numbers still to be confirmed by the car's reaction.
     */
    private fun sendServiceDiscoveryRequest(tls: TlsChannel) {
        val body = ProtoWriter()
            .string(4, "Morozi Auto Phone")
            .string(5, "Morozi")
            .toByteArray()
        sendMessage(CONTROL_CHANNEL, ControlMessage.SERVICE_DISCOVERY_REQUEST, body, tls)
        Log.i(TAG, "Sent SERVICE_DISCOVERY_REQUEST (${body.size} byte body)")
    }

    /**
     * Captured reply (id 6): a list of field-1 entries, one per service, each
     * with its channel id in field 1. The video entry is the one whose field 3
     * (media sink) carries field 4 (video configurations); codec 3 there is
     * H.264.
     */
    private fun readServiceDiscoveryResponse(tls: TlsChannel): List<Service> {
        val response = readUntil(CONTROL_CHANNEL, ControlMessage.SERVICE_DISCOVERY_RESPONSE, tls)
        val services = ProtoReader(response).messages(1).map { Service(it.varints(1).first().toInt(), it) }
        for (s in services) {
            Log.i(
                TAG,
                "Service ch=${s.channel} fields=${s.proto.fields().map { it.number }} codec=${s.codec} " +
                    "video=${s.isVideo} input=${s.isInput} audio=${s.audioSampleRate}Hz/${s.audioChannels}ch"
            )
        }
        require(services.any { it.isVideo }) { "Car offered no video service" }
        return services
    }

    /** Tells the car which of its keycodes we want; it echoes a status. */
    private fun bindKeys(input: Service, tls: TlsChannel) {
        val body = ProtoWriter().apply { input.keycodes.forEach { varint(1, it) } }.toByteArray()
        sendMessage(input.channel, InputMessage.KEY_BINDING_REQUEST, body, tls)
        val response = readUntil(input.channel, InputMessage.KEY_BINDING_RESPONSE, tls)
        Log.i(TAG, "KEY_BINDING_RESPONSE on ${input.channel}: ${response.toHex()}")
    }

    /**
     * Asks for permanent audio focus before playing on the media channel. Not
     * waited on: if the car never answers, the session must not stall here; the
     * reply (if any) shows up in the incoming log.
     */
    private fun requestAudioFocus(tls: TlsChannel) {
        sendMessage(CONTROL_CHANNEL, ControlMessage.AUDIO_FOCUS_REQUEST, ProtoWriter().varint(1, AUDIO_FOCUS_GAIN).toByteArray(), tls)
        Log.i(TAG, "Sent AUDIO_FOCUS_REQUEST")
    }

    /** Phone asks the car to open one service's channel; body: priority, channel id. */
    private fun openChannel(channel: Int, tls: TlsChannel) {
        val body = ProtoWriter().varint(1, 0).varint(2, channel.toLong()).toByteArray()
        sendMessage(channel, ControlMessage.CHANNEL_OPEN_REQUEST, body, tls, control = true)
        Log.i(TAG, "Sent CHANNEL_OPEN_REQUEST for channel $channel")
        val response = readUntil(channel, ControlMessage.CHANNEL_OPEN_RESPONSE, tls)
        Log.i(TAG, "CHANNEL_OPEN_RESPONSE on $channel: status=${ProtoReader(response).varints(1)}")
    }

    /**
     * Media setup: we name the codec the car listed for this sink, the car
     * answers with CONFIG (status, max unacked messages), then we START.
     */
    private fun setUpMedia(service: Service, tls: TlsChannel, configIndex: Int = 0): MediaStream {
        val channel = service.channel
        sendMessage(channel, MediaMessage.SETUP, ProtoWriter().varint(1, service.codec).toByteArray(), tls)
        val config = ProtoReader(readUntil(channel, MediaMessage.CONFIG, tls))
        val maxUnacked = config.varints(2).firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
        Log.i(TAG, "Media CONFIG on $channel: status=${config.varints(1)} maxUnacked=$maxUnacked configs=${config.varints(3)}")
        // START: field 1 session, field 2 which of the car's configurations we use.
        sendMessage(channel, MediaMessage.START, ProtoWriter().varint(1, 0).varint(2, configIndex.toLong()).toByteArray(), tls)
        return MediaStream(channel, maxUnacked, tls)
    }

    /**
     * Streams our generated picture. A second thread keeps reading what the car
     * sends meanwhile (acks, pings, touches) so nothing backs up.
     */
    private fun streamVideo(tls: TlsChannel) {
        val stream = videoStream ?: error("Video channel not set up")
        val config = videoConfig ?: error("Video config not chosen")
        val source = VideoSource(
            width = config.width, height = config.height, fps = config.fps,
            marginWidth = config.marginWidth, marginHeight = config.marginHeight, density = config.density,
            onCodecConfig = { sps -> stream.send(MediaMessage.CODEC_CONFIG, sps) },
            onFrame = { timestampUs, data ->
                val body = ByteBuffer.allocate(8 + data.size).putLong(timestampUs).put(data).array()
                stream.send(MediaMessage.DATA, body)
            }
        )
        videoSource = source
        val listener = Thread({
            try {
                while (running) handleIncoming(readMessage(tls), tls)
            } catch (t: Throwable) {
                Log.i(TAG, "Car side closed: ${t.message}")
            } finally {
                running = false
                source.stop()
            }
        }, "aap-incoming")
        listener.start()
        source.run()
        listener.interrupt()
    }

    private fun handleIncoming(message: AapMessage, tls: TlsChannel) {
        when {
            message.channel == CONTROL_CHANNEL && message.id == ControlMessage.PING_REQUEST -> {
                // Echo the car's timestamp back.
                val timestamp = ProtoReader(message.body).varints(1).firstOrNull() ?: 0
                sendMessage(CONTROL_CHANNEL, ControlMessage.PING_RESPONSE, ProtoWriter().varint(1, timestamp).toByteArray(), tls)
            }
            message.id == MediaMessage.ACK -> when (message.channel) {
                videoStream?.channel -> videoStream?.onAck(message.body)
                audioStream?.channel -> audioStream?.onAck(message.body)
            }
            message.channel == inputChannel && message.id == InputMessage.INPUT_EVENT -> onInputEvent(message.body)
            else -> Log.i(TAG, "In: ch=${message.channel} id=0x${message.id.toString(16)} ${message.body.size} bytes: ${message.body.take(32).toByteArray().toHex()}")
        }
    }

    /**
     * Touch from the car. Body read as: field 1 timestamp, field 3 touch event
     * {field 1 pointer {x, y, id}, field 2 action index, field 3 action}, with
     * action 0 = down, 1 = up (Android MotionEvent values; to be confirmed).
     */
    private fun onInputEvent(body: ByteArray) {
        val touch = ProtoReader(body).messages(3).firstOrNull()
        if (touch == null) {
            Log.i(TAG, "Input event (not touch): ${body.toHex()}")
            return
        }
        val pointer = touch.messages(1).firstOrNull()
        val x = pointer?.varints(1)?.firstOrNull() ?: -1
        val y = pointer?.varints(2)?.firstOrNull() ?: -1
        val action = touch.varints(3).firstOrNull() ?: -1
        Log.i(TAG, "Touch action=$action at ($x, $y)")
        if (action == TOUCH_ACTION_DOWN) {
            videoSource?.showTouch(x.toFloat(), y.toFloat())
            // Off the reader thread: sending waits on ACKs that this thread reads.
            Thread({ playBeep() }, "aap-beep").start()
        }
    }

    /** A short tone on the media audio channel, so a touch is heard as well as seen. */
    private fun playBeep() {
        val stream = audioStream ?: return
        val rate = audioSampleRate
        val samples = rate / 4
        val pcm = ByteBuffer.allocate(samples * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val v = (Math.sin(2 * Math.PI * 880 * i / rate) * 8000).toInt().toShort()
            pcm.putShort(v).putShort(v) // left, right
        }
        val bytes = pcm.array()
        val startUs = System.nanoTime() / 1000
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + AUDIO_CHUNK_BYTES, bytes.size)
            val timestampUs = startUs + offset / 4 * 1_000_000L / rate
            val body = ByteBuffer.allocate(8 + end - offset).putLong(timestampUs).put(bytes, offset, end - offset).array()
            stream.send(MediaMessage.DATA, body)
            offset = end
        }
    }

    /** Reads messages until [id] arrives on [channel], logging and answering pings on the way. */
    private fun readUntil(channel: Int, id: Int, tls: TlsChannel): ByteArray {
        while (true) {
            val message = readMessage(tls)
            if (message.channel == channel && message.id == id) return message.body
            handleIncoming(message, tls)
        }
    }

    private class AapMessage(val channel: Int, val id: Int, val body: ByteArray)

    /** Reads one whole message, reassembling it if it came in several frames. */
    private fun readMessage(tls: TlsChannel): AapMessage {
        val plain = java.io.ByteArrayOutputStream()
        var channel: Int
        do {
            val frame = reader.readFrame()
            channel = frame.channelId
            plain.write(if (frame.isEncrypted) tls.decrypt(frame.payload) else frame.payload)
        } while (!frame.isLastChunk)
        val bytes = plain.toByteArray()
        val id = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        return AapMessage(channel, id, bytes.copyOfRange(2, bytes.size))
    }

    /**
     * Encrypts and sends one message, splitting it over several frames when it
     * is bigger than [MAX_FRAME_PLAINTEXT]. Each frame is encrypted on its own.
     */
    private fun sendMessage(channel: Int, messageId: Int, body: ByteArray, tls: TlsChannel, control: Boolean = false) {
        val plain = ByteBuffer.allocate(2 + body.size).putShort(messageId.toShort()).put(body).array()
        val baseFlags = AapFrame.FLAG_ENCRYPTED or (if (control) AapFrame.FLAG_CONTROL else 0)
        synchronized(writer) {
            if (plain.size <= MAX_FRAME_PLAINTEXT) {
                writer.writeFrame(AapFrame(channel, baseFlags or AapFrame.FLAG_UNFRAGMENTED, tls.encrypt(plain)))
                return
            }
            var offset = 0
            while (offset < plain.size) {
                val end = minOf(offset + MAX_FRAME_PLAINTEXT, plain.size)
                var flags = baseFlags
                if (offset == 0) flags = flags or AapFrame.FLAG_FIRST_CHUNK
                if (end == plain.size) flags = flags or AapFrame.FLAG_LAST_CHUNK
                // Total size: plaintext length of the whole message (unconfirmed).
                val cipher = tls.encrypt(plain.copyOfRange(offset, end))
                writer.writeFrame(AapFrame(channel, flags, cipher), totalSize = plain.size)
                offset = end
            }
        }
    }

    private fun AapFrame.messageId(): Int =
        ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    private fun performTlsHandshake(): TlsChannel {
        val engine = TlsChannel.serverEngine()
        val tls = TlsChannel(
            engine = engine,
            // TLS bytes travel inside an SSL_HANDSHAKE control message: a two-byte
            // message id in front of the raw TLS record(s). Unconfirmed against a
            // live peer until the next run.
            readCipherText = {
                val frame = reader.readFrame()
                require(frame.channelId == CONTROL_CHANNEL) { "Expected control channel, got ${frame.channelId}" }
                Log.i(TAG, "TLS frame in: flags=0x${frame.flags.toString(16)} ${frame.payload.size} bytes, head=${frame.payload.take(8).toByteArray().toHex()}")
                val messageId = ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                if (messageId == ControlMessage.SSL_HANDSHAKE) {
                    frame.payload.copyOfRange(2, frame.payload.size)
                } else {
                    // After the handshake, encrypted frames may carry bare TLS
                    // records with no message id in front (unconfirmed).
                    frame.payload
                }
            },
            writeCipherText = { bytes ->
                val message = ByteBuffer.allocate(2 + bytes.size)
                    .putShort(ControlMessage.SSL_HANDSHAKE.toShort())
                    .put(bytes)
                    .array()
                Log.i(TAG, "TLS frame out: ${message.size} bytes")
                writer.writeFrame(AapFrame(CONTROL_CHANNEL, AapFrame.FLAG_UNFRAGMENTED, message))
            }
        )
        tls.handshake()
        Log.i(TAG, "TLS handshake complete: ${engine.session.cipherSuite}")
        return tls
    }

    fun shutdown() {
        running = false
    }
}
