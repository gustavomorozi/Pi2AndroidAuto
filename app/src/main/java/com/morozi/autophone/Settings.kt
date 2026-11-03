package com.morozi.autophone

import android.content.Context

/**
 * What the app needs to know about the car, kept on the device so it works
 * with no screen to ask on. Set from the Pi's own screen ([MainActivity]) or,
 * headless, over ADB ([ConfigReceiver]).
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The car's own Wi-Fi network (what a phone joins for wireless Android Auto). */
    var carWifiSsid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var carWifiPassword: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    /**
     * [MODE_LISTEN]: the head unit connects to us (Open Headunit style).
     * [MODE_CONNECT]: we connect to the head unit at [carHost]:[carPort].
     */
    var mode: String
        get() = prefs.getString(KEY_MODE, MODE_LISTEN) ?: MODE_LISTEN
        set(v) = prefs.edit().putString(KEY_MODE, v).apply()

    var carHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HOST, v).apply()

    var carPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    var listenPort: Int
        get() = prefs.getInt(KEY_LISTEN_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_LISTEN_PORT, v).apply()

    /** Upper bound on the video height we encode: 480, 720 or 1080. */
    var maxVideoHeight: Int
        get() = prefs.getInt(KEY_MAX_HEIGHT, 720)
        set(v) = prefs.edit().putInt(KEY_MAX_HEIGHT, v).apply()

    val maxVideoPixels: Int get() = maxVideoHeight * maxVideoHeight * 16 / 9

    /** One line for logs and the status screen; never includes the password. */
    fun describe(): String =
        "modo=$mode wifi=${carWifiSsid.ifEmpty { "-" }} senha=${if (carWifiPassword.isEmpty()) "não" else "sim"} " +
            "carro=${carHost.ifEmpty { "-" }}:$carPort escuta=$listenPort vídeo≤${maxVideoHeight}p"

    companion object {
        const val MODE_LISTEN = "listen"
        const val MODE_CONNECT = "connect"
        const val DEFAULT_PORT = 5277

        const val KEY_SSID = "car_wifi_ssid"
        const val KEY_PASSWORD = "car_wifi_password"
        const val KEY_MODE = "mode"
        const val KEY_HOST = "car_host"
        const val KEY_PORT = "car_port"
        const val KEY_LISTEN_PORT = "listen_port"
        const val KEY_MAX_HEIGHT = "max_video_height"
    }
}
