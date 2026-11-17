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
            "repair" -> repair(adapter, args[1], args[2], args.getOrNull(3)?.toIntOrNull() ?: 1)
            else -> println("usage: list | unpair <address> | repair <name> <address> <type>")
        }
    }

    /**
     * The remote came back under [address]: forget its old pairings (same name,
     * other address), then pair the new one, as Settings does by hand. Exits
     * once it is paired and connected, or after ~40 s.
     */
    private fun repair(adapter: BluetoothAdapter, name: String, address: String, type: Int) {
        adapter.bondedDevices.filter { it.name == name && !it.address.equals(address, true) }.forEach { old ->
            val removed = old.javaClass.getMethod("removeBond").invoke(old)
            println("forgot old pairing ${old.address}: $removed")
        }
        // Pairing the new address is left to the app: Android refuses a
        // pairing asked by root (uid 0 has no app identity), forgetting works.
        println("old pairings forgotten")
    }

    /** BluetoothManager built on a stand-in Context that says "root" (uid 0). */
    private fun adapter(): BluetoothAdapter? = runCatching {
        // A process started with app_process skips the app start-up that hands
        // the Bluetooth framework its service manager; do that step here.
        val init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
        val managerClass = Class.forName("android.os.BluetoothServiceManager")
        runCatching {
            init.getMethod("setBluetoothServiceManager", managerClass)
                .invoke(null, managerClass.getDeclaredConstructor().newInstance())
        }.onFailure { println("service manager: ${it.cause ?: it}") }
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
