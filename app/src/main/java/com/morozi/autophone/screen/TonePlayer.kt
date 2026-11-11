package com.morozi.autophone.screen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Test tool: plays a 440 Hz tone through Android's audio (as media), to check
 * that the car gets the sound Android plays. Run on the Pi with:
 *
 *   CLASSPATH=$(pm path com.morozi.autophone | cut -d: -f2) \
 *     app_process /system/bin com.morozi.autophone.screen.TonePlayer [seconds]
 */
object TonePlayer {
    private const val RATE = 48000

    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.firstOrNull()?.toIntOrNull() ?: 10
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(RATE) // ~0.25 s
            .build()
        track.play()
        val second = ShortArray(RATE * 2)
        for (i in 0 until RATE) {
            val v = (sin(2 * PI * 440 * i / RATE) * 12000).toInt().toShort()
            second[2 * i] = v
            second[2 * i + 1] = v
        }
        repeat(seconds) { track.write(second, 0, second.size) }
        track.stop()
        track.release()
        println("tone done")
    }
}
