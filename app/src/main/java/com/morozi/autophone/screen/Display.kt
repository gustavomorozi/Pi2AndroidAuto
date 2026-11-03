package com.morozi.autophone.screen

import android.hardware.display.VirtualDisplay
import android.view.Surface

/**
 * The main display, through framework calls that are hidden from apps but
 * available to a privileged process started with app_process.
 */
internal object Display {

    private const val MAIN_DISPLAY = 0

    /** Logical size of the main display (what goes out on HDMI), in pixels. */
    fun size(): Pair<Int, Int> {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(global, MAIN_DISPLAY)
        val cls = info.javaClass
        return cls.getField("logicalWidth").getInt(info) to cls.getField("logicalHeight").getInt(info)
    }

    /**
     * A virtual display of [width]x[height] that mirrors the main display into
     * [surface]; the framework scales the picture to fit, keeping its shape.
     * Uses DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror,
     * surface), a hidden static method (Android 14+).
     */
    fun mirror(name: String, width: Int, height: Int, surface: Surface): VirtualDisplay {
        val method = Class.forName("android.hardware.display.DisplayManager").getMethod(
            "createVirtualDisplay",
            String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Surface::class.java
        )
        return method.invoke(null, name, width, height, MAIN_DISPLAY, surface) as VirtualDisplay
    }
}
