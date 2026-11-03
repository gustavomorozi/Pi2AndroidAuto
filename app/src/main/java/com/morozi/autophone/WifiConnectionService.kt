package com.morozi.autophone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "WifiConnection"
private const val NOTIFICATION_CHANNEL_ID = "wifi_connection"
private const val NOTIFICATION_ID = 2

/** Port a head unit opens when it connects to a phone over the network. */
private const val AAP_TCP_PORT = 5277

/**
 * Wireless counterpart of [AccessoryConnectionService]: waits for a head unit
 * to connect over TCP, then hands the socket's streams to the same [AapSession]
 * the USB path uses. The protocol above the transport is identical; only how
 * the bytes arrive differs.
 */
class WifiConnectionService : Service() {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Aguardando o carro na porta $AAP_TCP_PORT"))
        if (!listening) {
            listening = true
            thread(name = "aap-wifi-accept") { acceptLoop() }
        }
        return START_STICKY
    }

    private fun acceptLoop() {
        try {
            // Bind IPv4 explicitly: the default wildcard socket was IPv6-only on
            // this build, so even a loopback IPv4 connection never completed.
            val socketServer = ServerSocket(AAP_TCP_PORT, 50, InetAddress.getByName("0.0.0.0")).also { server = it }
            Log.i(TAG, "Listening on TCP $AAP_TCP_PORT")
            while (listening) {
                val socket = socketServer.accept()
                Log.i(TAG, "Head unit connected from ${socket.inetAddress.hostAddress}")
                runSession(socket)
            }
        } catch (e: Exception) {
            if (listening) Log.e(TAG, "Accept loop failed", e)
        } finally {
            listening = false
        }
    }

    /** One connection at a time: a car only ever opens one session. */
    private fun runSession(socket: Socket) {
        socket.use {
            socket.tcpNoDelay = true
            val session = AapSession(RawLogInputStream(socket.getInputStream()), socket.getOutputStream()) { reason ->
                Log.i(TAG, "Session ended: $reason")
            }
            session.run()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listening = false
        server?.close()
        server = null
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
            .setContentTitle("Morozi Auto Phone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
