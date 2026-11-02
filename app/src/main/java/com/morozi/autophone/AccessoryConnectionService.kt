package com.morozi.autophone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

private const val TAG = "AccessoryConnection"
private const val NOTIFICATION_CHANNEL_ID = "accessory_connection"
private const val NOTIFICATION_ID = 1

/**
 * Owns the USB accessory file descriptor for as long as this device is plugged
 * into a car. Protocol handling itself lives in [AapSession]; this class is just
 * the connection lifecycle (open, run, close) and the foreground-service plumbing
 * Android requires for a long-lived USB session.
 */
class AccessoryConnectionService : Service() {

    private var accessoryFd: ParcelFileDescriptor? = null
    private var sessionThread: Thread? = null
    @Volatile private var session: AapSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accessory: UsbAccessory? = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        if (accessory == null) {
            Log.w(TAG, "Started without an accessory extra; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Conectado: ${accessory.model}"))
        openAccessory(accessory)
        return START_NOT_STICKY
    }

    private fun openAccessory(accessory: UsbAccessory) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val fd = usbManager.openAccessory(accessory)
        if (fd == null) {
            Log.e(TAG, "openAccessory() returned null for ${accessory.model}")
            stopSelf()
            return
        }
        accessoryFd = fd

        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        val aapSession = AapSession(input, output) { reason ->
            Log.i(TAG, "Session ended: $reason")
            stopSelf()
        }
        session = aapSession

        sessionThread = thread(name = "aap-session") {
            aapSession.run()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.shutdown()
        accessoryFd?.close()
        accessoryFd = null
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Conexão com o carro",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Morozi Auto Phone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
