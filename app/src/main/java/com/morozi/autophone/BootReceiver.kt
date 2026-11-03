package com.morozi.autophone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log

private const val TAG = "BootReceiver"

/**
 * Runs when the device finishes booting. A car on a single cable starts talking
 * to us as soon as our USB gadget comes up, which can be before the system has
 * launched the activity for the accessory attach broadcast. Starting our process
 * here gets it ready early, and picks up an accessory that is already attached.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        context.startForegroundService(Intent(context, WifiConnectionService::class.java))

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = usbManager.accessoryList?.firstOrNull()
        if (accessory == null) {
            Log.i(TAG, "Boot completed; no accessory attached yet.")
            return
        }
        if (!usbManager.hasPermission(accessory)) {
            Log.w(TAG, "Boot completed; accessory attached but no permission to open it.")
            return
        }

        context.startForegroundService(
            Intent(context, AccessoryConnectionService::class.java).apply {
                putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            }
        )
    }
}
