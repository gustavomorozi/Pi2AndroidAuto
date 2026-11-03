package com.morozi.autophone.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
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

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Taps at screen pixel ([x], [y]); returns true if it activated something. */
    fun tap(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in REMOTE_ONLY_APPS) return false
        val target = deepestAt(root, x, y)?.let { itemAt(it) } ?: return false
        // Like the remote: move focus to the item, then press OK. TV apps often
        // handle OK on the item row itself and ignore a click on its inner card.
        val focused = target.isFocused || target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val how = if (focused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)) "ok" else "ok failed"
        } else {
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "click" else "click failed"
        }
        Log.i(TAG, "tap ($x, $y) in $pkg -> ${target.className} ${target.viewIdResourceName ?: ""} focused=$focused $how")
        return !how.endsWith("failed")
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
        @Volatile var instance: TapAssistService? = null
            private set

        /** Apps that act on remote focus only and ignore taps. */
        val REMOTE_ONLY_APPS = setOf(
            "org.lineageos.tv.launcher",
            "com.android.tv.settings",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.android.tv.launcher",
        )
    }
}
