package com.morozi.autophone.screen

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Touches and keys for one display, by its id. The virtual touchscreen and
 * keyboard of [ScreenServer] always act on the main display; in split screen
 * each app is on its own virtual display, so events are handed to the input
 * system with the display they are for. Uses InputManager.injectInputEvent
 * and InputEvent.setDisplayId, hidden from apps; the screen server (root) may.
 */
internal object DisplayInput {

    private val manager: Any
    private val inject: java.lang.reflect.Method
    private val setDisplayId = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)

    init {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        manager = cls.getMethod("getInstance").invoke(null)!!
        inject = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    /** One finger at ([x], [y]) display pixels; [action] is a MotionEvent action. */
    fun touch(displayId: Int, action: Int, x: Int, y: Int, downTime: Long): Boolean {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        return send(event, displayId).also { event.recycle() }
    }

    /** A key pressed and released on the display. */
    fun key(displayId: Int, code: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).all { action ->
            send(KeyEvent(now, now, action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD), displayId)
        }
    }

    private fun send(event: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(event, displayId)
        return inject.invoke(manager, event, MODE_ASYNC) as Boolean
    }

    private const val MODE_ASYNC = 0
}
