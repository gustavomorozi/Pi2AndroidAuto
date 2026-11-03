package com.morozi.autophone.screen

/**
 * Where the scaled screen sits inside the car's video (scaled to fit, keeping
 * its shape, the rest black), so car touches can be mapped back to screen
 * pixels. Shared by [ScreenServer] (virtual touchscreen) and the app
 * (accessibility clicks).
 */
data class ScreenFit(
    val left: Int, val top: Int, val width: Int, val height: Int,
    val screenWidth: Int, val screenHeight: Int
) {
    /** Screen pixel for a video point, or null in the black bars. */
    fun toScreen(x: Int, y: Int): Pair<Int, Int>? {
        val cx = x - left
        val cy = y - top
        if (cx < 0 || cy < 0 || cx >= width || cy >= height) return null
        return cx * screenWidth / width to cy * screenHeight / height
    }

    companion object {
        fun of(screenWidth: Int, screenHeight: Int, videoWidth: Int, videoHeight: Int): ScreenFit {
            val scale = minOf(videoWidth.toFloat() / screenWidth, videoHeight.toFloat() / screenHeight)
            val w = (screenWidth * scale).toInt()
            val h = (screenHeight * scale).toInt()
            return ScreenFit((videoWidth - w) / 2, (videoHeight - h) / 2, w, h, screenWidth, screenHeight)
        }
    }
}
