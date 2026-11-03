package com.morozi.autophone.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.concurrent.thread

private const val TAG = "ScreenServer"

/**
 * Privileged helper started by init at boot (see tools/pi/morozi_screen.sh):
 *
 *   CLASSPATH=<our apk> app_process /system/bin com.morozi.autophone.screen.ScreenServer
 *
 * Running outside the app sandbox (as root), it can do two things an app
 * cannot without asking the user on screen, which a car has no way to do:
 * - mirror the whole Android TV screen (what goes out on HDMI) into a virtual
 *   display that feeds an H.264 encoder at the car's resolution;
 * - drive a virtual HID touchscreen, so a touch on the car is a real touch to
 *   Android, in any app. (The CarlinKit phone role creates virtual HID
 *   touchscreens the same way; screen capture differs because the Pi composes
 *   the screen on the GPU.)
 *
 * The Android Auto app talks to it over the abstract local socket
 * [SOCKET_NAME]; see [ScreenProtocol].
 */
object ScreenServer {

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        Log.i(TAG, "starting; display ${Display.size()}")
        val server = LocalServerSocket(ScreenProtocol.SOCKET_NAME)
        val touch = runCatching { HidTouchscreen.create() }
            .onFailure { Log.e(TAG, "virtual touchscreen failed", it) }.getOrNull()
        while (true) {
            val client = server.accept()
            Log.i(TAG, "app connected")
            thread(name = "screen-session") {
                runCatching { Session(client, touch).run() }
                    .onFailure { Log.i(TAG, "session ended: ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    /** One car session: encode the mirrored screen and apply the car's touches. */
    private class Session(private val socket: LocalSocket, private val touch: HidTouchscreen?) {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream.buffered())
        @Volatile private var running = true

        fun run() {
            require(input.readUnsignedByte() == ScreenProtocol.START) { "expected START" }
            val width = input.readInt()
            val height = input.readInt()
            val fps = input.readInt()
            val bitrate = input.readInt()
            val screen = Display.size()
            // The screen is scaled to fit inside the video, keeping its shape;
            // the rest is black. Touches are mapped back through this box.
            val fit = ScreenFit.of(screen.first, screen.second, width, height)
            Log.i(TAG, "start ${width}x$height@$fps, screen ${screen.first}x${screen.second}, content $fit")

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // A still screen produces no new frames; repeat the last one so
                // the car keeps getting video (it watches for it).
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            // Mirror into a GPU texture that FrameRelay redraws into the
            // encoder at a fixed rate, so a still screen still sends video.
            val relay = FrameRelay(surface, width, height)
            val relayThread = thread(name = "screen-relay") {
                runCatching { relay.run(fps) }.onFailure { Log.e(TAG, "frame relay failed", it); running = false }
            }
            val mirror = Display.mirror("morozi-car", width, height, relay.awaitInputSurface())

            val touchThread = thread(name = "screen-touch") { readTouches(fit, screen) }
            try {
                drain(codec)
            } finally {
                running = false
                relay.stop()
                relayThread.join(1000)
                runCatching { mirror.release() }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                surface.release()
                touchThread.interrupt()
                touch?.up()
            }
        }

        private fun readTouches(fit: ScreenFit, screen: Pair<Int, Int>) {
            try {
                while (running) {
                    when (input.readUnsignedByte()) {
                        ScreenProtocol.TOUCH -> {
                            val action = input.readUnsignedByte()
                            val x = input.readInt()
                            val y = input.readInt()
                            val (sx, sy) = fit.toScreen(x, y) ?: continue
                            when (action) {
                                ScreenProtocol.ACTION_UP -> touch?.up()
                                else -> touch?.down(sx, sy, screen.first, screen.second)
                            }
                        }
                        else -> return
                    }
                }
            } catch (e: Exception) {
                running = false
            }
        }

        private fun drain(codec: MediaCodec) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index) ?: continue
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                codec.releaseOutputBuffer(index, false)
                if (data.isEmpty()) continue
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                output.writeByte(if (config) ScreenProtocol.CODEC_CONFIG else ScreenProtocol.FRAME)
                output.writeLong(info.presentationTimeUs)
                output.writeInt(data.size)
                output.write(data)
                output.flush()
            }
        }
    }
}
