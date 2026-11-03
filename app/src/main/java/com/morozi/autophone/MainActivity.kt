package com.morozi.autophone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/** Gives the service a moment to open the settings page's port on first launch. */
private const val SETTINGS_PAGE_DELAY_MS = 800L

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
            textSize = 18f
            setPadding(48, 24, 48, 8)
            text = "Pi2AndroidAuto — aguardando o carro (USB ou Wi‑Fi)"
        }
        // The same settings page phones reach over the network, shown here so
        // the Pi can be configured from its own screen too.
        val settingsPage = WebView(this).apply {
            settings.javaScriptEnabled = false
            webViewClient = WebViewClient()
            postDelayed({ loadUrl("http://127.0.0.1:$CONFIG_WEB_PORT/") }, SETTINGS_PAGE_DELAY_MS)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(settingsPage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        requestNearbyWifiPermission()
        startForegroundService(Intent(this, WifiConnectionService::class.java))

        handleIntent(intent)
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            attachToExistingAccessory()
        }
    }

    /** The setup hotspot needs the nearby Wi-Fi devices permission (a runtime one). */
    private fun requestNearbyWifiPermission() {
        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 1)
        }
    }

    /**
     * The attach broadcast fires once. If the car already switched us into
     * accessory mode before this app was running, pick that accessory up here.
     */
    private fun attachToExistingAccessory() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val accessory = usbManager.accessoryList?.firstOrNull() ?: return
        if (!usbManager.hasPermission(accessory)) return

        statusView.text = "Conectado a: ${accessory.manufacturer} / ${accessory.model}\nIniciando sessão..."
        startForegroundService(
            Intent(this, AccessoryConnectionService::class.java).apply {
                putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            }
        )
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
