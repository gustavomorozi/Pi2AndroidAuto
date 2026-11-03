package com.morozi.autophone

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle

/**
 * Receives the system's "accessory attached" intent when a car switches us into
 * accessory mode (Android delivers it, with permission granted, only to an
 * activity), hands the accessory to [AccessoryConnectionService] and closes at
 * once. Keeping this out of [MainActivity] means a car attach never brings up
 * the settings screen nor triggers its own pick-up of the accessory as a second
 * start.
 */
class AccessoryAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward(intent)
        finish()
    }

    private fun forward(intent: Intent?) {
        val accessory: UsbAccessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) ?: return
        startForegroundService(
            Intent(this, AccessoryConnectionService::class.java).putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        )
    }
}
