package com.morozi.autophone

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.util.Log

private const val TAG = "CarWifi"

/**
 * Joining the car's Wi-Fi and the Pi's own setup network.
 */
object CarWifi {

    /** Last setup network we created, shown on the Pi's screen and in the log. */
    @Volatile var setupNetwork: String? = null
        private set

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Hands the car's network to Android as a suggestion, so the Pi joins it on
     * its own whenever it is in range. Android may ask once on screen to allow
     * this app's suggestions.
     */
    fun applyCarNetwork(context: Context) {
        val settings = Settings(context)
        val wifi = context.getSystemService(WifiManager::class.java)
        wifi.removeNetworkSuggestions(emptyList())
        if (settings.carWifiSsid.isEmpty()) return

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(settings.carWifiSsid)
            .apply { if (settings.carWifiPassword.isNotEmpty()) setWpa2Passphrase(settings.carWifiPassword) }
            .setIsAppInteractionRequired(false)
            .build()
        val status = wifi.addNetworkSuggestions(listOf(suggestion))
        Log.i(TAG, "Car network suggested: ${settings.carWifiSsid} (status $status)")
    }

    /**
     * Setup mode: the Pi creates its own Wi-Fi so a phone can reach the settings
     * page with no other network around. Android picks the name and password;
     * they are shown on the Pi's screen and logged. Joining it drops the Pi off
     * any network it was on until the hotspot stops.
     */
    fun startSetupHotspot(context: Context) {
        val wifi = context.getSystemService(WifiManager::class.java)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val config = r.softApConfiguration
                    setupNetwork = "Wi‑Fi \"${config.ssid}\", senha ${config.passphrase}"
                    Log.i(TAG, "Setup hotspot up: $setupNetwork")
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "Setup hotspot failed: $reason")
                }
            }, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Setup hotspot needs the nearby Wi-Fi permission", e)
        }
    }

    /** The Pi's IPv4 addresses (Wi-Fi, hotspot, Ethernet), for the settings page link. */
    fun localAddresses(): List<String> =
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress ?: "" }

    fun stopSetupHotspot() {
        reservation?.close()
        reservation = null
        setupNetwork = null
    }
}
