package com.morozi.autophone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "ConfigReceiver"

/**
 * Headless settings over ADB, for when there is no screen at all:
 *
 *   adb shell am broadcast -a com.morozi.autophone.CONFIGURE -n com.morozi.autophone/.ConfigReceiver \
 *       --es ssid "CarWifi" --es password "..." --es mode connect --es host 192.168.1.1 --ei port 5277
 *
 * Split screen on the car, for tests (two apps side by side, then back):
 *
 *   adb shell am broadcast -a com.morozi.autophone.SPLIT -n com.morozi.autophone/.ConfigReceiver \
 *       --es left org.smarttube.stable --es right com.android.documentsui [--ei percent 50]
 *   adb shell am broadcast -a com.morozi.autophone.SPLIT -n com.morozi.autophone/.ConfigReceiver --ez stop true
 *
 * Only the shell (ADB) and the system can send it: the manifest guards it with
 * the DUMP permission, which ordinary apps cannot hold.
 */
class ConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SPLIT) {
            if (intent.getBooleanExtra("stop", false)) {
                com.morozi.autophone.screen.SplitScreen.stop()
            } else {
                val ok = com.morozi.autophone.screen.SplitScreen.start(
                    context, intent.getStringExtra("left").orEmpty(), intent.getStringExtra("right").orEmpty(),
                    intent.getIntExtra("percent", 50)
                )
                Log.i(TAG, "Split screen requested: ${if (ok) "sent" else "not possible"}")
            }
            return
        }
        if (intent.action != ACTION_CONFIGURE) return
        val s = Settings(context)
        intent.getStringExtra("ssid")?.let { s.carWifiSsid = it }
        intent.getStringExtra("password")?.let { s.carWifiPassword = it }
        intent.getStringExtra("mode")?.takeIf { it == Settings.MODE_LISTEN || it == Settings.MODE_CONNECT }?.let { s.mode = it }
        intent.getStringExtra("host")?.let { s.carHost = it }
        if (intent.hasExtra("quality")) s.videoQuality = intent.getIntExtra("quality", 100)
        if (intent.hasExtra("port")) s.carPort = intent.getIntExtra("port", s.carPort)
        if (intent.hasExtra("listen_port")) s.listenPort = intent.getIntExtra("listen_port", s.listenPort)
        if (intent.hasExtra("max_height")) s.maxVideoHeight = intent.getIntExtra("max_height", s.maxVideoHeight)
        if (intent.getBooleanExtra("setup_hotspot", false)) CarWifi.startSetupHotspot(context)
        Log.i(TAG, "Saved: ${s.describe()}")

        // Restart the connection service so the new settings take effect.
        context.startForegroundService(Intent(context, WifiConnectionService::class.java).setAction(WifiConnectionService.ACTION_RELOAD))
    }

    companion object {
        const val ACTION_CONFIGURE = "com.morozi.autophone.CONFIGURE"
        const val ACTION_SPLIT = "com.morozi.autophone.SPLIT"
    }
}
