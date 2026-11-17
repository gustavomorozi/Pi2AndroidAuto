package com.morozi.autophone.screen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Process

/**
 * Root tool for the Bluetooth remote (run as root with app_process):
 *
 *   CLASSPATH=$(pm path com.morozi.autophone | cut -d: -f2) \
 *     app_process /system/bin com.morozi.autophone.screen.BtTool list|unpair <address>
 *
 * `unpair` forgets a paired device, which an app cannot do (it needs a system
 * permission). Forgetting the remote's old pairing is what lets it pair again
 * when it comes back under a new address.
 */
@SuppressLint("MissingPermission")
object BtTool {
    @JvmStatic
    fun main(args: Array<String>) {
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper()
        val adapter = adapter() ?: return println("no Bluetooth adapter")
        when (args.firstOrNull()) {
            "list" -> adapter.bondedDevices.forEach {
                println("${it.address} name=${it.name} type=${it.type} class=${it.bluetoothClass} bond=${it.bondState}")
            }
            "unpair" -> {
                val device = adapter.getRemoteDevice(args[1])
                val removed = device.javaClass.getMethod("removeBond").invoke(device)
                println("removeBond ${args[1]} -> $removed")
            }
            else -> println("usage: list | unpair <address>")
        }
    }

    /** BluetoothManager built on a stand-in Context that says "root" (uid 0). */
    private fun adapter(): BluetoothAdapter? = runCatching {
        val constructor = BluetoothManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        constructor.newInstance(RootContext()).adapter
    }.onFailure { println("Bluetooth not reachable: ${it.cause ?: it}"); (it.cause ?: it).stackTrace.take(6).forEach { f -> println("  at $f") } }.getOrNull()

    private class RootContext : ContextWrapper(null) {
        override fun getPackageName(): String = "root"
        override fun getOpPackageName(): String = "root"
        override fun getAttributionTag(): String? = null
        override fun getApplicationContext(): Context = this
        override fun getDeviceId(): Int = 0
        override fun createDeviceContext(deviceId: Int): Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName("root").build()
    }
}
