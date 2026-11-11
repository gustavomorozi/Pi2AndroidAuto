package com.morozi.autophone.screen

/**
 * Local socket protocol between the Android Auto app and [ScreenServer].
 *
 * App -> server:
 *   START  : u8 0x01, i32 width, i32 height, i32 fps, i32 bitrate, i32 audio rate (0 = no audio)
 *   TOUCH  : u8 0x02, u8 action (0 down, 1 up, 2 move), i32 x, i32 y  (video coordinates)
 *   KEY    : u8 0x03, i32 Android key code, u8 repeat  (arrow keys for remote-only apps)
 *   SPLIT_START : u8 0x04, UTF left activity, UTF right activity ("package/class"), u8 left width percent
 *   SPLIT_STOP  : u8 0x05  (back to the mirrored screen)
 * Server -> app:
 *   CODEC_CONFIG / FRAME : u8 type, i64 ptsUs, i32 length, bytes (H.264)
 *   AUDIO  : u8 0x12, i64 ptsUs, i32 length, bytes (PCM 16-bit LE stereo; what Android plays)
 *   SPLIT_STATE : u8 0x13, i64 0, i32 1, u8 (1 = two apps side by side, 0 = mirrored screen)
 * Closing the socket ends the session.
 */
object ScreenProtocol {
    const val SOCKET_NAME = "morozi_screen"

    const val START = 0x01
    const val TOUCH = 0x02
    const val KEY = 0x03
    const val SPLIT_START = 0x04
    const val SPLIT_STOP = 0x05
    const val CODEC_CONFIG = 0x10
    const val FRAME = 0x11
    const val AUDIO = 0x12
    const val SPLIT_STATE = 0x13

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    /** Second finger: drop the touch so the app does not get a tap. */
    const val ACTION_CANCEL = 3
}
