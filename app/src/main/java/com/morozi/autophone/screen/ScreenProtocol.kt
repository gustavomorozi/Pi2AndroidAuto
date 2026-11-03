package com.morozi.autophone.screen

/**
 * Local socket protocol between the Android Auto app and [ScreenServer].
 *
 * App -> server:
 *   START  : u8 0x01, i32 width, i32 height, i32 fps, i32 bitrate
 *   TOUCH  : u8 0x02, u8 action (0 down, 1 up, 2 move), i32 x, i32 y  (video coordinates)
 * Server -> app:
 *   CODEC_CONFIG / FRAME : u8 type, i64 ptsUs, i32 length, bytes (H.264)
 * Closing the socket ends the session.
 */
object ScreenProtocol {
    const val SOCKET_NAME = "morozi_screen"

    const val START = 0x01
    const val TOUCH = 0x02
    const val CODEC_CONFIG = 0x10
    const val FRAME = 0x11

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
}
