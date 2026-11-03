package com.morozi.autophone

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "VideoSource"

/**
 * Generates our own screen content and encodes it to H.264. This is the point
 * of the project: the picture is drawn here, not mirrored from anywhere.
 *
 * [onCodecConfig] receives the SPS/PPS once; [onFrame] receives each encoded
 * frame with its presentation time in microseconds.
 */
class VideoSource(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onCodecConfig: (ByteArray) -> Unit,
    private val onFrame: (timestampUs: Long, data: ByteArray) -> Unit
) {
    @Volatile private var running = false

    /** Runs until [stop] is called or the encoder fails. Blocks the caller. */
    fun run() {
        running = true
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        Log.i(TAG, "Encoder: ${codec.name} ${width}x$height@$fps")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val drawer = Thread({ drawLoop(surface) }, "aap-video-draw")
        drawer.start()
        try {
            drainLoop(codec)
        } finally {
            running = false
            drawer.join(1000)
            codec.stop()
            codec.release()
            surface.release()
        }
    }

    fun stop() {
        running = false
    }

    private fun drawLoop(surface: android.view.Surface) {
        val background = Paint().apply { color = Color.rgb(16, 32, 64) }
        val box = Paint().apply { color = Color.rgb(255, 140, 0) }
        val title = Paint().apply {
            color = Color.WHITE
            textSize = height / 9f
            isAntiAlias = true
        }
        val small = Paint(title).apply { textSize = height / 18f }
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val frameMs = 1000L / fps
        var n = 0L
        while (running) {
            val started = System.currentTimeMillis()
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
                val side = height / 6f
                val x = (n * 8 % (width - side).toLong()).toFloat()
                canvas.drawRect(x, height * 0.7f, x + side, height * 0.7f + side, box)
                canvas.drawText("Pi2AndroidAuto", width * 0.08f, height * 0.3f, title)
                canvas.drawText("Morozi • ${clock.format(Date())} • frame $n", width * 0.08f, height * 0.45f, small)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            n++
            val spent = System.currentTimeMillis() - started
            if (spent < frameMs) Thread.sleep(frameMs - spent)
        }
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var frames = 0
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 100_000)
            if (index < 0) continue
            val buffer = codec.getOutputBuffer(index) ?: continue
            val data = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(data)
            codec.releaseOutputBuffer(index, false)
            if (data.isEmpty()) continue

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                Log.i(TAG, "Codec config: ${data.size} bytes")
                onCodecConfig(data)
            } else {
                onFrame(info.presentationTimeUs, data)
                if (++frames % (fps * 5) == 0) Log.i(TAG, "Sent $frames frames")
            }
        }
    }
}
