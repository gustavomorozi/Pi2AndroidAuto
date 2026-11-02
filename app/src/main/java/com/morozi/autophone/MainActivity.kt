package com.morozi.autophone

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.TextView

/**
 * Entry point. Launched normally (shows status) or by the system when this device
 * is plugged into a host that speaks the Android Open Accessory protocol and
 * identifies itself with the strings declared in res/xml/accessory_filter.xml.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            textSize = 20f
            setPadding(48, 48, 48, 48)
            text = "Morozi Auto Phone\n\nAguardando conexão USB com um carro..."
        }
        setContentView(statusView)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return

        val accessory: UsbAccessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY) ?: run {
            statusView.text = "Acessório USB anexado, mas sem detalhes do dispositivo."
            return
        }

        statusView.text = "Conectado a: ${accessory.manufacturer} / ${accessory.model}\nIniciando sessão..."

        val serviceIntent = Intent(this, AccessoryConnectionService::class.java).apply {
            putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        }
        startForegroundService(serviceIntent)
    }
}
