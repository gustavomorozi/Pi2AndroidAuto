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
}

private const val VERSION_STATUS_SUCCESS = 0x0000

/** How many post-TLS messages [AapSession.observeAfterTls] logs before giving up. */
private const val OBSERVE_MESSAGE_LIMIT = 10

/**
 * Drives one Android Auto session over an already-open USB accessory stream
 * pair, playing the "phone" role: the car sends the version request and opens
 * TLS as the client; we answer the version request and are the TLS server.
 *
 * Current state: version handshake and TLS handshake are implemented and were
 * never run end-to-end against a real head unit yet -- the next concrete step
 * is to test this against our own Open Headunit (acting as the car) over a
 * USB-C cable, since we already know that side works. Everything after the
 * TLS handshake (service discovery, channel setup, video/audio streaming) is
 * intentionally not written yet; see the TODO at the bottom of [run].
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
            observeEncrypted(tls)
            // TODO next steps, in order:
            //   1. Send AUTH_COMPLETE on the control channel once TLS is up.
            //   2. Send our ServiceDiscoveryResponse advertising a video sink... no --
            //      we are the phone, so WE send a request and the car responds with
            //      which services (video, audio, input, sensors) it wants from us.
            //   3. Open a video channel, start a MediaCodec H.264 encoder, and feed it
            //      whatever content we decide to show (this is the actual point of the
            //      whole project -- generated content, not a mirrored phone screen).
            //   4. Handle input-channel touch/button events coming back from the car.
            onEnded("not-yet-implemented: stopped after TLS handshake")
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
        sendEncrypted(CONTROL_CHANNEL, ControlMessage.SERVICE_DISCOVERY_REQUEST, body, tls)
        Log.i(TAG, "Sent SERVICE_DISCOVERY_REQUEST (${body.size} byte body)")
    }

    private fun sendEncrypted(channel: Int, messageId: Int, body: ByteArray, tls: TlsChannel) {
        val plain = ByteBuffer.allocate(2 + body.size)
            .putShort(messageId.toShort())
            .put(body)
            .array()
        val flags = AapFrame.FLAG_UNFRAGMENTED or AapFrame.FLAG_ENCRYPTED
        writer.writeFrame(AapFrame(channel, flags, tls.encrypt(plain)))
    }

    /**
     * Temporary: log what the car answers (decrypted) instead of guessing, until
     * the next phase is written. Ends when the car closes the connection.
     */
    private fun observeEncrypted(tls: TlsChannel) {
        repeat(OBSERVE_MESSAGE_LIMIT) {
            val frame = reader.readFrame()
            val plain = if (frame.isEncrypted) tls.decrypt(frame.payload) else frame.payload
            if (plain.size < 2) {
                Log.i(TAG, "Frame ch=${frame.channelId} flags=0x${frame.flags.toString(16)}: ${plain.size} plain bytes")
                return@repeat
            }
            val messageId = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
            Log.i(
                TAG,
                "Frame ch=${frame.channelId} flags=0x${frame.flags.toString(16)} id=$messageId " +
                    "${plain.size - 2} byte body"
            )
            // Whole body, in pieces short enough for one logcat line each.
            plain.drop(2).chunked(200).forEachIndexed { i, piece ->
                Log.i(TAG, "  body[$i]: ${piece.toByteArray().toHex()}")
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
