package com.morozi.autophone.screen

/**
 * Split screen: where the two apps sit inside the car's video. Both halves
 * fill the screen box of [ScreenFit] (left | thin divider | right), each one
 * a virtual display of exactly that many pixels, so a video pixel is a display
 * pixel. A small square handle in the middle of the divider goes back to the
 * full screen. Shared by [FrameRelay] (drawing) and [ScreenServer] (touches).
 */
internal data class SplitLayout(
    val top: Int, val height: Int,
    val leftX: Int, val leftWidth: Int,
    val dividerX: Int, val dividerWidth: Int,
    val rightX: Int, val rightWidth: Int
) {
    val handleSize: Int get() = (height / 12).coerceIn(28, 64)
    val handleX: Int get() = dividerX + dividerWidth / 2 - handleSize / 2
    val handleY: Int get() = top + height / 2 - handleSize / 2

    /** True if video point ([x], [y]) is on the "full screen" handle (a little slack for fingers). */
    fun onHandle(x: Int, y: Int): Boolean =
        x >= handleX - HANDLE_SLACK && x < handleX + handleSize + HANDLE_SLACK &&
            y >= handleY - HANDLE_SLACK && y < handleY + handleSize + HANDLE_SLACK

    /** True if video x is on the right app. */
    fun isRight(x: Int): Boolean = x >= dividerX + dividerWidth / 2

    /**
     * Density for both displays: the narrower half is about [TARGET_DP] dp
     * wide, like a phone held upright, so apps lay out for a narrow screen
     * with finger-sized items (a 640 px half gets 256 dpi, a 400 px one 160).
     */
    val density: Int get() = (160 * minOf(leftWidth, rightWidth) / TARGET_DP).coerceIn(120, 480)

    companion object {
        private const val DIVIDER = 4
        private const val HANDLE_SLACK = 8
        private const val TARGET_DP = 400

        fun of(fit: ScreenFit, leftPercent: Int): SplitLayout {
            val usable = fit.width - DIVIDER
            // Even widths: some apps and the GPU are happier with them.
            val left = (usable * leftPercent.coerceIn(25, 75) / 100) and 1.inv()
            return SplitLayout(
                top = fit.top, height = fit.height,
                leftX = fit.left, leftWidth = left,
                dividerX = fit.left + left, dividerWidth = DIVIDER,
                rightX = fit.left + left + DIVIDER, rightWidth = usable - left
            )
        }
    }
}
