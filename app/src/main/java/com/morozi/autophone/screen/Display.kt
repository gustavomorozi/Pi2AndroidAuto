package com.morozi.autophone.screen

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
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

    /**
     * A virtual display with its own content (split screen: one app lives on
     * it), drawn into [surface]. Trusted, so apps can be started on it and get
     * touches. When it is removed its apps are closed, not moved to the main
     * display: an app that keeps its own layout scale (SmartTube) came back
     * to the TV screen drawn for the small display, with tiny items.
     */
    fun own(name: String, width: Int, height: Int, density: Int, surface: Surface): VirtualDisplay {
        val config = VirtualDisplayConfig.Builder(name, width, height, density)
            .setFlags(OWN_FLAGS).setSurface(surface).build()
        // DisplayManagerGlobal.createVirtualDisplay(context, projection, config,
        // callback, executor): the public DisplayManager call needs a full
        // Context, which a bare app_process does not have. Arguments are
        // filled by type, as the list has changed between Android versions.
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val method = global.methods.firstOrNull { m ->
            m.name == "createVirtualDisplay" && m.parameterTypes.any { it == VirtualDisplayConfig::class.java }
        } ?: error("no DisplayManagerGlobal.createVirtualDisplay")
        val args = method.parameterTypes.map { type ->
            when (type) {
                Context::class.java -> RootContext
                VirtualDisplayConfig::class.java -> config
                else -> null
            }
        }.toTypedArray()
        return method.invoke(global.getMethod("getInstance").invoke(null), *args) as? VirtualDisplay
            ?: error("virtual display $name refused (${method.toGenericString()})")
    }

    /** DisplayManager only asks the context who is calling; root has no package of its own. */
    private object RootContext : ContextWrapper(null) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
    }

    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val OWN_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
        FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TRUSTED or FLAG_ALWAYS_UNLOCKED
}
