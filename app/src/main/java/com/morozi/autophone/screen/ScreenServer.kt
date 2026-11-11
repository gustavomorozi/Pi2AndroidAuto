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
        val keys = runCatching { HidKeyboard.create() }
            .onFailure { Log.e(TAG, "virtual keyboard failed", it) }.getOrNull()
        while (true) {
            val client = server.accept()
            Log.i(TAG, "app connected")
            thread(name = "screen-session") {
                runCatching { Session(client, touch, keys).run() }
                    .onFailure { Log.i(TAG, "session ended: ${it.message}") }
                runCatching { client.close() }
            }
        }
    }

    private val ROOT_TASK = Regex("""^RootTask id=(\d+) .*displayId=(\d+)""")

    /** One car session: encode the mirrored screen and apply the car's touches. */
    private class Session(private val socket: LocalSocket, private val touch: HidTouchscreen?, private val keys: HidKeyboard?) {
        private val input = DataInputStream(socket.inputStream)
        private val output = DataOutputStream(socket.outputStream.buffered())
        @Volatile private var running = true

        fun run() {
            require(input.readUnsignedByte() == ScreenProtocol.START) { "expected START" }
            val width = input.readInt()
            val height = input.readInt()
            val fps = input.readInt()
            val bitrate = input.readInt()
            val audioRate = input.readInt()
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

            this.relay = relay
            val touchThread = thread(name = "screen-touch") { readTouches(fit, screen) }
            // What Android plays goes to the car too (its media audio channel).
            val audio = if (audioRate > 0) {
                AudioCapture(audioRate) { pts, pcm -> runCatching { write(ScreenProtocol.AUDIO, pts, pcm) } }
            } else null
            val audioThread = audio?.let { thread(name = "screen-audio") { it.run() } }
            try {
                drain(codec)
            } finally {
                running = false
                audio?.stop()
                audioThread?.join(500)
                // The car left: the two apps close with their displays.
                runCatching { stopSplit(notify = false) }
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
                            if (split != null) {
                                splitTouch(action, x, y)
                                continue
                            }
                            val (sx, sy) = fit.toScreen(x, y) ?: continue
                            when (action) {
                                ScreenProtocol.ACTION_UP -> touch?.up()
                                ScreenProtocol.ACTION_CANCEL -> touch?.cancel()
                                else -> touch?.down(sx, sy, screen.first, screen.second)
                            }
                        }
                        ScreenProtocol.KEY -> {
                            val code = input.readInt()
                            val repeat = input.readUnsignedByte().coerceIn(1, 12)
                            val side = split?.let { if (lastRight) it.right else it.left }
                            if (side != null) {
                                Log.i(TAG, "split key $code x$repeat -> display ${side.id}")
                                for (i in 0 until repeat) DisplayInput.key(side.id, code)
                            } else {
                                pressKey(code, repeat)
                            }
                        }
                        ScreenProtocol.SPLIT_START -> {
                            val left = input.readUTF()
                            val right = input.readUTF()
                            val percent = input.readUnsignedByte()
                            runCatching { startSplit(fit, left, right, percent) }
                                .onFailure { Log.e(TAG, "split screen failed", it) }
                        }
                        ScreenProtocol.SPLIT_STOP -> stopSplit(notify = true)
                        else -> return
                    }
                }
            } catch (e: Exception) {
                running = false
            }
        }

        // ---- Split screen: two apps side by side, each on its own virtual display.

        private class Side(val display: android.hardware.display.VirtualDisplay, val x: Int, val width: Int, val component: String) {
            val id: Int get() = display.display.displayId
        }

        private class Split(val layout: SplitLayout, val left: Side, val right: Side)

        private var relay: FrameRelay? = null
        @Volatile private var split: Split? = null
        /** The app touched last: Back (and any other key) goes to it. */
        private var lastRight = false
        /** The app the finger went down on, until it lifts. */
        private var gestureSide: Side? = null
        private var gestureOnHandle = false
        private var gestureDownAt = 0L

        private fun startSplit(fit: ScreenFit, left: String, right: String, leftPercent: Int) {
            val relay = relay ?: return
            stopSplit(notify = false)
            touch?.up()
            val layout = SplitLayout.of(fit, leftPercent)
            val (leftSurface, rightSurface) = relay.startSplit(layout)
            var leftDisplay: android.hardware.display.VirtualDisplay? = null
            val created = try {
                leftDisplay = Display.own("morozi-split-left", layout.leftWidth, layout.height, layout.density, leftSurface)
                val rightDisplay = Display.own("morozi-split-right", layout.rightWidth, layout.height, layout.density, rightSurface)
                Split(layout, Side(leftDisplay, layout.leftX, layout.leftWidth, left), Side(rightDisplay, layout.rightX, layout.rightWidth, right))
            } catch (e: Exception) {
                // Never leave the car on two empty halves.
                runCatching { leftDisplay?.release() }
                relay.stopSplit()
                throw e
            }
            lastRight = false
            gestureSide = null
            gestureOnHandle = false
            split = created
            Log.i(TAG, "split on: $left on display ${created.left.id} (${layout.leftWidth}x${layout.height}), " +
                "$right on display ${created.right.id} (${layout.rightWidth}x${layout.height}), ${layout.density} dpi")
            runCatching { write(ScreenProtocol.SPLIT_STATE, 0, byteArrayOf(1)) }
            thread(name = "screen-split-apps") {
                // Right first, so the left app ends up with the focus (keys go to it until a touch).
                startOn(created.right.id, right)
                startOn(created.left.id, left)
            }
        }

        /**
         * Opens activity [component] ("package/class") on the display, from
         * scratch. Tasks the app already has are closed first: left open, the
         * app brought its old task on the main screen to the front and the
         * half stayed empty; moved over as they were, an app that keeps its
         * own layout scale (SmartTube) stayed drawn for the other display.
         */
        private fun startOn(displayId: Int, component: String) {
            runCatching {
                val pkg = component.substringBefore('/')
                val closed = openTasksOf(pkg).map { (rootTask, _) ->
                    shell("am", "stack", "remove", rootTask.toString())
                    rootTask
                }
                val text = shell(
                    "am", "start", "--display", displayId.toString(), "--windowingMode", "1",
                    "-a", "android.intent.action.MAIN", "-n", component
                ).replace('\n', ' ')
                Log.i(TAG, "start $component on display $displayId (closed tasks $closed): $text")
            }.onFailure { Log.w(TAG, "could not start $component on display $displayId", it) }
        }

        /** Root task id and display id of every open task of [pkg], read from `am stack list`. */
        private fun openTasksOf(pkg: String): List<Pair<Int, Int>> {
            val found = mutableListOf<Pair<Int, Int>>()
            var current: Pair<Int, Int>? = null
            for (line in shell("am", "stack", "list").lines()) {
                val root = ROOT_TASK.find(line)
                if (root != null) {
                    current = root.groupValues[1].toInt() to root.groupValues[2].toInt()
                } else if (current != null && line.contains(": $pkg/") && line.trimStart().startsWith("taskId=")) {
                    found.add(current)
                    current = null
                }
            }
            return found
        }

        private fun shell(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            return text
        }

        private fun stopSplit(notify: Boolean) {
            val old = split ?: return
            split = null
            runCatching { old.left.display.release() }
            runCatching { old.right.display.release() }
            relay?.stopSplit()
            Log.i(TAG, "split off")
            if (!notify) return
            runCatching { write(ScreenProtocol.SPLIT_STATE, 0, byteArrayOf(0)) }
            // "Full screen": the displays took their apps with them, so the
            // app touched last is opened again, on the whole screen this time.
            val component = (if (lastRight) old.right else old.left).component
            thread(name = "screen-split-apps") {
                runCatching {
                    Log.i(TAG, "full screen: ${shell("am", "start", "-a", "android.intent.action.MAIN", "-n", component).replace('\n', ' ')}")
                }
            }
        }

        /** A car touch in split screen: to the app under it, or the handle that ends the split. */
        private fun splitTouch(action: Int, x: Int, y: Int) {
            val current = split ?: return
            val layout = current.layout
            if (action == ScreenProtocol.ACTION_DOWN) {
                gestureOnHandle = layout.onHandle(x, y)
                gestureSide = if (gestureOnHandle) null else if (layout.isRight(x)) current.right else current.left
                gestureDownAt = android.os.SystemClock.uptimeMillis()
                if (!gestureOnHandle) lastRight = gestureSide === current.right
            }
            if (gestureOnHandle) {
                if (action == ScreenProtocol.ACTION_UP) {
                    gestureOnHandle = false
                    if (layout.onHandle(x, y)) stopSplit(notify = true)
                }
                return
            }
            val side = gestureSide ?: return
            val px = (x - side.x).coerceIn(0, side.width - 1)
            val py = (y - layout.top).coerceIn(0, layout.height - 1)
            val motion = when (action) {
                ScreenProtocol.ACTION_DOWN -> android.view.MotionEvent.ACTION_DOWN
                ScreenProtocol.ACTION_UP -> android.view.MotionEvent.ACTION_UP
                ScreenProtocol.ACTION_CANCEL -> android.view.MotionEvent.ACTION_CANCEL
                else -> android.view.MotionEvent.ACTION_MOVE
            }
            val ok = runCatching { DisplayInput.touch(side.id, motion, px, py, gestureDownAt) }
                .onFailure { Log.w(TAG, "split touch failed", it) }.getOrDefault(false)
            if (motion != android.view.MotionEvent.ACTION_MOVE) {
                Log.i(TAG, "split touch action $motion at ($px, $py) -> display ${side.id} ${if (side === current.right) "right" else "left"} ($ok)")
            }
            if (motion == android.view.MotionEvent.ACTION_UP || motion == android.view.MotionEvent.ACTION_CANCEL) gestureSide = null
        }

        /**
         * Key presses: through the virtual keyboard (press and release together,
         * so a slow app cannot see a long press); keys it has no usage for fall
         * back to `input keyevent`.
         */
        private fun pressKey(code: Int, repeat: Int) {
            thread(name = "screen-key") {
                runCatching {
                    if (keys?.press(code, repeat) != true) {
                        val command = arrayOf("input", "keyevent") + Array(repeat) { code.toString() }
                        Runtime.getRuntime().exec(command).waitFor()
                    }
                }.onFailure { Log.w(TAG, "key press failed", it) }
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
                write(if (config) ScreenProtocol.CODEC_CONFIG else ScreenProtocol.FRAME, info.presentationTimeUs, data)
            }
        }

        /** One message to the app; the video and audio threads both write. */
        private fun write(type: Int, ptsUs: Long, data: ByteArray) {
            try {
                synchronized(output) {
                    output.writeByte(type)
                    output.writeLong(ptsUs)
                    output.writeInt(data.size)
                    output.write(data)
                    output.flush()
                }
            } catch (e: java.io.IOException) {
                running = false
                throw e
            }
        }
    }
}
