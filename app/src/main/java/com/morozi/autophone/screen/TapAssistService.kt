package com.morozi.autophone.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "TapAssist"

/**
 * Makes car taps work in Android TV apps built for the remote only.
 *
 * The car's touches reach Android as real touches (virtual touchscreen), but
 * TV launchers and TV settings ignore taps: they act on focus and OK. For a
 * short tap in one of those apps, this service finds the item under the
 * tapped point and focuses and activates it, like moving the remote there and
 * pressing OK. Touch-capable apps are left to the real touch, so nothing is
 * clicked twice.
 *
 * Enabled with adb (no screen in the car):
 *   settings put secure enabled_accessibility_services com.morozi.autophone/com.morozi.autophone.screen.TapAssistService
 *   settings put secure accessibility_enabled 1
 */
class TapAssistService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var controls: CarControls? = null

    override fun onServiceConnected() {
        CarVolume.load(this)
        controls = CarControls(this)
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        controls?.hide()
        super.onDestroy()
    }

    /** Shows the floating button while the car shows the mirrored screen. */
    fun setCarConnected(connected: Boolean) = main.post {
        if (connected) controls?.show() else controls?.hide()
        Log.i(TAG, "floating button ${if (connected) "shown" else "hidden"}")
    }

    /** Any car touch: the floating button comes back for a while. */
    fun onCarTouch() = main.post { controls?.touched() }

    /** True if screen pixel ([x], [y]) is on the floating button or its menu. */
    fun onControls(x: Int, y: Int): Boolean {
        val result = java.util.concurrent.FutureTask { controls?.contains(x, y) == true }
        main.post(result)
        return runCatching { result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** Two-finger tap from the car. */
    fun back() {
        // On the main thread, a moment after the cancelled touch settles (the
        // touchscreen was just recreated); sent at once it was lost.
        main.postDelayed({
            val done = performGlobalAction(GLOBAL_ACTION_BACK)
            Log.i(TAG, "two-finger tap -> back ($done)")
        }, BACK_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * A drag in a remote-only app: those apps scroll with arrow keys, not with
     * a finger. Returns the arrow key code and how many presses ([SWIPE_STEP_PX]
     * pixels each, up to [SWIPE_MAX_STEPS]) for a drag of [dx], [dy] screen
     * pixels, or null when the front app takes touch itself or the drag is
     * too short. Content follows the finger: dragging up moves the focus down.
     * The server makes the presses ([ScreenClient.key]).
     */
    fun swipeKeys(dx: Int, dy: Int): Pair<Int, Int>? {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return null
        if (pkg !in REMOTE_ONLY_APPS) return null
        val vertical = kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        val distance = if (vertical) kotlin.math.abs(dy) else kotlin.math.abs(dx)
        if (distance < SWIPE_MIN_PX) return null
        val steps = (distance / SWIPE_STEP_PX).coerceIn(1, SWIPE_MAX_STEPS)
        val key = when {
            vertical && dy < 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            vertical -> KeyEvent.KEYCODE_DPAD_UP
            dx < 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_DPAD_LEFT
        }
        Log.i(TAG, "swipe ($dx, $dy) in $pkg -> $steps x key $key")
        return key to steps
    }

    /**
     * True while the launcher's item menu ("Abrir / Mover / Remover dos
     * favoritos") is on screen. A slow Pi can make an OK press look like a long
     * press, which opens that menu instead of the app; its "Abrir" entry is
     * selected already, so one more OK opens the app.
     */
    fun itemMenuShown(): Boolean = windows.any { window ->
        val root = window.root ?: return@any false
        MENU_MARKERS.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
    }

    /** True while the front app is one that only reacts to the remote (see [REMOTE_ONLY_APPS]). */
    fun frontIsRemoteOnly(): Boolean = rootInActiveWindow?.packageName?.toString() in REMOTE_ONLY_APPS

    /**
     * Taps at screen pixel ([x], [y]) in a remote-only app: moves the focus to
     * the item there. Returns true when the caller should now press OK (a real
     * key through the screen server: an accessibility OK was read by the Google
     * TV launcher as a long press and opened "Remover dos favoritos").
     * Where focus cannot be moved, the item is clicked instead and false is returned.
     */
    fun tap(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in REMOTE_ONLY_APPS) return false
        val target = deepestAt(root, x, y)?.let { itemAt(it) } ?: return false
        // Like the remote: move focus to the item, then press OK. TV apps often
        // handle OK on the item row itself and ignore a click on its inner card.
        val wasFocused = target.isFocused
        val focused = wasFocused || target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Moving the focus slides the row; an OK during that animation is
        // dropped by the launcher, so let it settle first.
        if (focused && !wasFocused) Thread.sleep(FOCUS_SETTLE_MS)
        val how = if (focused) {
            "focused, OK to press"
        } else if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "clicked"
        } else {
            "click failed"
        }
        Log.i(TAG, "tap ($x, $y) in $pkg -> ${target.className} ${target.viewIdResourceName ?: ""} $how")
        return focused
    }

    private fun deepestAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (!bounds.contains(x, y) || !node.isVisibleToUser) return null
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            deepestAt(child, x, y)?.let { return it }
        }
        return node
    }

    /** The outermost focusable/clickable ancestor below the list that holds it. */
    private fun itemAt(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var item: AccessibilityNodeInfo? = null
        var node: AccessibilityNodeInfo? = start
        while (node != null && node.collectionInfo == null && !node.isScrollable) {
            if (node.isFocusable || node.isClickable) item = node
            node = node.parent
        }
        return item
    }

    companion object {
        private const val BACK_DELAY_MS = 300L

        @Volatile var instance: TapAssistService? = null
            private set

        /** Apps that act on remote focus only and ignore taps. */
        val REMOTE_ONLY_APPS = setOf(
            "org.lineageos.tv.launcher",
            "com.android.tv.settings",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.android.tv.launcher",
            "com.android.vending",
            "com.ionitech.airscreen",
        )
        private val MENU_MARKERS = listOf("Remover dos favoritos", "Remove from favorites")
        private const val FOCUS_SETTLE_MS = 300L
        private const val SWIPE_MIN_PX = 90
        private const val SWIPE_STEP_PX = 220
        private const val SWIPE_MAX_STEPS = 6
    }
}
