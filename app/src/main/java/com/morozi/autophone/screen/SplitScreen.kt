package com.morozi.autophone.screen

import android.content.Context
import android.util.Log

/**
 * Split screen from the app's side: two apps side by side on the car (the
 * Android TV build has no split screen of its own). Asks [ScreenServer],
 * through the car session's [ScreenClient], to put each app on its own
 * virtual display and show both instead of the mirrored screen.
 */
object SplitScreen {

    private const val TAG = "SplitScreen"

    /** True while the car shows two apps (touches then go to them, not to the main screen). */
    val active: Boolean get() = ScreenClient.active?.splitActive == true

    /** Starts it with the apps [leftPkg] and [rightPkg]; false if there is no car or an app cannot be opened. */
    fun start(context: Context, leftPkg: String, rightPkg: String, leftPercent: Int = 50): Boolean {
        val client = ScreenClient.active
        val left = RecentApps.launchComponent(context, leftPkg)
        val right = RecentApps.launchComponent(context, rightPkg)
        if (client == null || left == null || right == null || leftPkg == rightPkg) {
            Log.w(TAG, "cannot split $leftPkg | $rightPkg (car ${client != null}, left $left, right $right)")
            return false
        }
        Log.i(TAG, "split $left | $right, left $leftPercent%")
        client.split(left, right, leftPercent)
        return true
    }

    /** Back to the mirrored screen. */
    fun stop() {
        ScreenClient.active?.splitStop()
    }
}
