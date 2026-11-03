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

/** Codec value the car listed for its video sink (captured: 3). */
private const val VIDEO_CODEC_H264 = 3L
private const val VIDEO_FOCUS_PROJECTED = 1L

/** Keeps each frame's TLS record under the 16 KiB record limit. */
private const val MAX_FRAME_PLAINTEXT = 16000

/**
 * Drives one Android Auto session over an already-open USB accessory stream
 * pair, playing the "phone" role: the car sends the version request and opens
 * TLS as the client; we answer the version request and are the TLS server.
 *
 * Current state, tested against our own Open Headunit over Wi-Fi: version,
 * TLS, AUTH_COMPLETE and service discovery work. Opening the video channel and
 * streaming our generated picture is written but not yet confirmed; audio and
 * input are not written.
 */
class AapSession(
    rawInput: InputStream,
    rawOutput: OutputStream,
    private val onEnded: (reason: String) -> Unit
) {
    private val reader = AapFrameReader(rawInput)
    private val writer = AapFrameWriter(rawOutput)

    @Volatile private var running = true

    fun run() {
        try {
            performVersionHandshake()
            val tls = performTlsHandshake()

            readAuthComplete()
            sendServiceDiscoveryRequest(tls)
            val videoChannel = readServiceDiscoveryResponse(tls)

            openChannel(videoChannel, tls)
            setUpVideo(videoChannel, tls)
            streamVideo(videoChannel, tls)
            // Still to do: audio and input channels, touch events from the car.
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
    private fun readServiceDiscoveryResponse(tls: TlsChannel): Int {
        val response = readUntil(CONTROL_CHANNEL, ControlMessage.SERVICE_DISCOVERY_RESPONSE, tls)
        val services = ProtoReader(response).messages(1)
        for (service in services) {
            Log.i(TAG, "Service on channel ${service.varints(1).firstOrNull()}: fields ${service.fields().map { it.number }}")
        }
        val video = services.firstOrNull { service ->
            service.messages(3).any { sink -> sink.messages(4).isNotEmpty() }
        } ?: error("Car offered no video service")
        val channel = video.varints(1).first().toInt()
        val sink = video.messages(3).first()
        val config = sink.messages(4).first()
        Log.i(
            TAG,
            "Video on channel $channel: codec=${sink.varints(1)} resolution=${config.varints(1)} " +
                "frameRate=${config.varints(2)} density=${config.varints(5)}"
        )
        return channel
    }

    /** Phone asks the car to open one service's channel; body: priority, channel id. */
    private fun openChannel(channel: Int, tls: TlsChannel) {
        val body = ProtoWriter().varint(1, 0).varint(2, channel.toLong()).toByteArray()
        sendMessage(channel, ControlMessage.CHANNEL_OPEN_REQUEST, body, tls, control = true)
        Log.i(TAG, "Sent CHANNEL_OPEN_REQUEST for channel $channel")
        val response = readUntil(channel, ControlMessage.CHANNEL_OPEN_RESPONSE, tls)
        Log.i(TAG, "CHANNEL_OPEN_RESPONSE on $channel: status=${ProtoReader(response).varints(1)}")
    }

    /** Media setup: we name the codec, the car answers with its config (status, window). */
    private fun setUpVideo(channel: Int, tls: TlsChannel) {
        sendMessage(channel, MediaMessage.SETUP, ProtoWriter().varint(1, VIDEO_CODEC_H264).toByteArray(), tls)
        Log.i(TAG, "Sent media SETUP on channel $channel")
        val config = ProtoReader(readUntil(channel, MediaMessage.CONFIG, tls))
        Log.i(TAG, "Media CONFIG: status=${config.varints(1)} maxUnacked=${config.varints(2)} configs=${config.varints(3)}")

        sendMessage(channel, MediaMessage.START, ProtoWriter().varint(1, 0).varint(2, 0).toByteArray(), tls)
        sendMessage(
            channel,
            MediaMessage.VIDEO_FOCUS_INDICATION,
            ProtoWriter().varint(1, VIDEO_FOCUS_PROJECTED).varint(2, 0).toByteArray(),
            tls
        )
        Log.i(TAG, "Sent media START and VIDEO_FOCUS_INDICATION")
    }

    /**
     * Streams our generated picture. A second thread keeps reading what the car
     * sends meanwhile (acks, pings, focus requests) so nothing backs up.
     */
    private fun streamVideo(channel: Int, tls: TlsChannel) {
        val source = VideoSource(
            width = 1280, height = 720, fps = 30,
            onCodecConfig = { sps -> sendMessage(channel, MediaMessage.CODEC_CONFIG, sps, tls) },
            onFrame = { timestampUs, data ->
                val body = ByteBuffer.allocate(8 + data.size).putLong(timestampUs).put(data).array()
                sendMessage(channel, MediaMessage.DATA, body, tls)
            }
        )
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
            message.id == MediaMessage.ACK -> Unit // flow control; not enforced yet
            else -> Log.i(TAG, "In: ch=${message.channel} id=0x${message.id.toString(16)} ${message.body.size} bytes: ${message.body.take(32).toByteArray().toHex()}")
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
