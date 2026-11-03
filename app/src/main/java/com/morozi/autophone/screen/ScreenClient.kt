package com.morozi.autophone.screen

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream

private const val TAG = "ScreenClient"

/**
 * The app's side of [ScreenServer]: asks for the mirrored Android TV screen as
 * H.264 at the car's video size and forwards the car's touches. Same shape as
 * the test-pattern VideoSource ([run] blocks until [stop] or the link ends).
 */
class ScreenClient private constructor(
    private val socket: LocalSocket,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (timestampUs: Long, data: ByteArray) -> Unit
) {
    private val output = DataOutputStream(socket.outputStream)
    @Volatile private var running = true

    fun run() {
        synchronized(output) {
            output.writeByte(ScreenProtocol.START)
            output.writeInt(width); output.writeInt(height); output.writeInt(fps); output.writeInt(bitrate)
            output.flush()
        }
        val input = DataInputStream(socket.inputStream.buffered())
        var frames = 0
        try {
            while (running) {
                val type = input.readUnsignedByte()
                val pts = input.readLong()
                val data = ByteArray(input.readInt())
                input.readFully(data)
                when (type) {
                    ScreenProtocol.CODEC_CONFIG -> onCodecConfig(data)
                    ScreenProtocol.FRAME -> {
                        onFrame(pts, data)
                        if (++frames % (fps * 5) == 0) Log.i(TAG, "Sent $frames screen frames")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.i(TAG, "screen link ended: ${e.message}")
        } finally {
            stop()
        }
    }

    /** A car touch in video coordinates; action as in [ScreenProtocol]. */
    fun touch(action: Int, x: Int, y: Int) {
        runCatching {
            synchronized(output) {
                output.writeByte(ScreenProtocol.TOUCH)
                output.writeByte(action)
                output.writeInt(x); output.writeInt(y)
                output.flush()
            }
        }
    }

    fun stop() {
        running = false
        // close() alone does not wake a read blocked in another thread on a
        // LocalSocket; shutting the input down does (the session otherwise
        // lingered until the next car connected).
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    companion object {
        /** Connects to the screen server, or returns null if it isn't running. */
        fun connect(
            width: Int, height: Int, fps: Int, bitrate: Int,
            onCodecConfig: (ByteArray) -> Unit,
            onFrame: (timestampUs: Long, data: ByteArray) -> Unit
        ): ScreenClient? = runCatching {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(ScreenProtocol.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
            ScreenClient(socket, width, height, fps, bitrate, onCodecConfig, onFrame)
        }.onFailure { Log.i(TAG, "screen server not available: ${it.message}") }.getOrNull()
    }
}
