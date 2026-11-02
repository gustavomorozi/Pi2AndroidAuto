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
}

private const val VERSION_STATUS_SUCCESS = 0x0000

/**
 * Drives one Android Auto session over an already-open USB accessory stream
 * pair, playing the "phone" role: we are the side that initiates the version
 * handshake and (per the protocol) is the TLS client.
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
            performTlsHandshake()

            Log.i(TAG, "TLS established. Protocol beyond this point is not implemented yet.")
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
        writer.writeFrame(AapFrame(CONTROL_CHANNEL, AapFrame.FLAG_CONTROL, response))
        Log.i(TAG, "Sent VERSION_RESPONSE (messageId=$responseId) echoing $major.$minor, status=SUCCESS")
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private fun performTlsHandshake() {
        val engine = TlsChannel.defaultEngine(isClient = true)
        val tls = TlsChannel(
            engine = engine,
            readCipherText = {
                val frame = reader.readFrame()
                require(frame.channelId == CONTROL_CHANNEL)
                frame.payload
            },
            writeCipherText = { bytes ->
                writer.writeFrame(AapFrame(CONTROL_CHANNEL, AapFrame.FLAG_CONTROL or AapFrame.FLAG_ENCRYPTED, bytes))
            }
        )
        tls.handshake()
        Log.i(TAG, "TLS handshake complete: ${engine.session.cipherSuite}")
    }

    fun shutdown() {
        running = false
    }
}
