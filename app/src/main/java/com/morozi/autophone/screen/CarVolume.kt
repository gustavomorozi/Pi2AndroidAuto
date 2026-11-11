package com.morozi.autophone.screen

import android.content.Context

/**
 * Volume of the sound sent to the car, set from the floating button.
 *
 * The captured playback is the same level whatever Android's own volume says,
 * so the level is applied here, to the PCM on its way to the car. Kept across
 * restarts.
 */
object CarVolume {
    private const val PREFS = "car_volume"
    private const val KEY = "percent"
    const val STEP = 10

    @Volatile var percent = 100
        private set

    fun load(context: Context) {
        percent = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 100)
    }

    /** Moves the volume by [delta] percent (0..100) and returns the new value. */
    fun adjust(context: Context, delta: Int): Int {
        percent = (percent + delta).coerceIn(0, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, percent).apply()
        return percent
    }

    /** Scales 16-bit little-endian PCM in place by the current volume. */
    fun apply(pcm: ByteArray) {
        val level = percent
        if (level >= 100) return
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)) * level / 100
            pcm[i] = sample.toByte()
            pcm[i + 1] = (sample shr 8).toByte()
            i += 2
        }
    }
}
