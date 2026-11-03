package com.morozi.autophone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "WifiConnection"
private const val NOTIFICATION_CHANNEL_ID = "wifi_connection"
private const val NOTIFICATION_ID = 2

/** Pause between attempts to reach the car in connect mode. */
private const val RECONNECT_DELAY_MS = 5000L

/**
 * Wireless counterpart of [AccessoryConnectionService]. Depending on
 * [Settings.mode] it either waits for a head unit to connect over TCP (Open
 * Headunit style) or connects out to the car itself, then hands the socket's
 * streams to the same [AapSession] the USB path uses. Also serves the settings
 * page and joins the car's Wi-Fi from the saved settings.
 */
class WifiConnectionService : Service() {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var generation = 0
    private var web: ConfigWebServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = Settings(this)
        startForeground(NOTIFICATION_ID, buildNotification(settings.describe()))

        if (web == null) {
            web = ConfigWebServer(this) { reload() }.also { it.start() }
        }
        if (intent?.action == ACTION_RELOAD || generation == 0) reload()
        return START_STICKY
    }

    /** Applies the saved settings: join the car's Wi-Fi and (re)start the link loop. */
    private fun reload() {
        val settings = Settings(this)
        Log.i(TAG, "Settings: ${settings.describe()}")
        CarWifi.applyCarNetwork(this)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(settings.describe()))

        val myGeneration = ++generation
        server?.close()
        server = null
        if (settings.mode == Settings.MODE_CONNECT) {
            thread(name = "aap-wifi-connect") { connectLoop(myGeneration) }
        } else {
            thread(name = "aap-wifi-accept") { acceptLoop(myGeneration, settings.listenPort) }
        }
    }

    private fun acceptLoop(myGeneration: Int, port: Int) {
        try {
            // Bind IPv4 explicitly: the default wildcard socket was IPv6-only on
            // this build, so even a loopback IPv4 connection never completed.
            val socketServer = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).also { server = it }
            Log.i(TAG, "Listening on TCP $port")
            while (generation == myGeneration) {
                val socket = socketServer.accept()
                Log.i(TAG, "Head unit connected from ${socket.inetAddress.hostAddress}")
                runSession(socket)
            }
        } catch (e: Exception) {
            if (generation == myGeneration) Log.e(TAG, "Accept loop failed", e)
        }
    }

    /** Connect mode: keep trying the car's address until a session runs, then again after it ends. */
    private fun connectLoop(myGeneration: Int) {
        while (generation == myGeneration) {
            val settings = Settings(this)
            if (settings.carHost.isEmpty()) {
                Log.w(TAG, "Connect mode but no car address saved")
                return
            }
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(settings.carHost, settings.carPort), RECONNECT_DELAY_MS.toInt())
                Log.i(TAG, "Connected to car at ${settings.carHost}:${settings.carPort}")
                runSession(socket)
            } catch (e: Exception) {
                Log.i(TAG, "Car not reachable at ${settings.carHost}:${settings.carPort}: ${e.message}")
            }
            Thread.sleep(RECONNECT_DELAY_MS)
        }
    }

    /** One connection at a time: a car only ever opens one session. */
    private fun runSession(socket: Socket) {
        socket.use {
            socket.tcpNoDelay = true
            val session = AapSession(
                RawLogInputStream(socket.getInputStream()),
                socket.getOutputStream(),
                maxVideoPixels = Settings(this).maxVideoPixels
            ) { reason ->
                Log.i(TAG, "Session ended: $reason")
            }
            session.run()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        generation = -1
        server?.close()
        server = null
        web?.stop()
        web = null
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Conexão sem fio com o carro",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pi2AndroidAuto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RELOAD = "com.morozi.autophone.RELOAD"
    }
}
