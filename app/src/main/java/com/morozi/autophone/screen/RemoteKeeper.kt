package com.morozi.autophone.screen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

private const val TAG = "RemoteKeeper"

/**
 * Keeps the Bluetooth remote paired. The remote used here ("LE-7678") comes
 * back with a new address after the Pi restarts or after a while, as if it
 * were a new device, while Android waits for the old one: it never
 * reconnected by itself and had to be paired again by hand.
 *
 * While a paired remote (a Bluetooth LE remote control) is not connected, this
 * scans quietly for its name. When it shows up under a new address (someone
 * pressed a key on it), [onRemoteBack] runs the re-pairing ([RemoteRepairFlow]:
 * forget the old pairing, pair the new one), as it only works that way by hand.
 * Nothing appears on screen while the remote is away.
 *
 * Needs BLUETOOTH_SCAN and BLUETOOTH_CONNECT (granted by tools/pi/install.sh).
 */
@SuppressLint("MissingPermission")
internal class RemoteKeeper(
    private val context: Context,
    private val onRemoteBack: (name: String) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanning = false
    private var lastRepairAt = -REPAIR_GAP_MS

    /** True while a re-pairing runs: no scanning meanwhile. */
    @Volatile var busy = false

    /** True if a paired remote is connected now. */
    fun anyConnected(): Boolean = pairedRemotes().any(::isConnected)

    private val check = object : Runnable {
        override fun run() {
            runCatching { tick() }.onFailure { Log.w(TAG, "check failed: $it") }
            main.postDelayed(this, CHECK_MS)
        }
    }

    fun start() {
        main.post(check)
        Log.i(TAG, "started")
    }

    fun stop() {
        main.removeCallbacks(check)
        stopScan()
    }

    /** Remotes we were paired with: Bluetooth LE peripherals of the remote-control kind. */
    private fun pairedRemotes(): List<BluetoothDevice> =
        adapter?.bondedDevices.orEmpty().filter { device ->
            device.type == BluetoothDevice.DEVICE_TYPE_LE &&
                device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL &&
                !device.name.isNullOrEmpty()
        }

    private fun isConnected(device: BluetoothDevice): Boolean =
        runCatching { device.javaClass.getMethod("isConnected").invoke(device) as Boolean }.getOrDefault(false)

    private fun tick() {
        val bt = adapter ?: return
        if (!bt.isEnabled) return stopScan()
        val remotes = pairedRemotes()
        val missing = remotes.filterNot(::isConnected)
        if (remotes.isEmpty() || missing.isEmpty() || busy) return stopScan()
        if (!scanning) startScan(missing.mapNotNull { it.name }.distinct(), missing.map { it.address }.toSet())
    }

    private var callback: ScanCallback? = null

    private fun startScan(names: List<String>, oldAddresses: Set<String>) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.address in oldAddresses) return          // Android reconnects that one itself
                val now = SystemClock.elapsedRealtime()
                if (now - lastRepairAt < REPAIR_GAP_MS) return
                val name = result.scanRecord?.deviceName ?: return
                Log.i(TAG, "remote \"$name\" is back under a new address; re-pairing")
                stopScan()
                lastRepairAt = now
                main.post { onRemoteBack(name) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                scanning = false
            }
        }
        val filters = names.map { ScanFilter.Builder().setDeviceName(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(filters, settings, cb) }
            .onSuccess {
                callback = cb
                scanning = true
                Log.i(TAG, "remote ${names.joinToString()} not connected; watching for it")
            }
            .onFailure { Log.w(TAG, "could not scan: $it") }
    }

    private fun stopScan() {
        val cb = callback ?: return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        callback = null
        scanning = false
    }

    private companion object {
        const val CHECK_MS = 15_000L
        const val REPAIR_GAP_MS = 180_000L
    }
}
