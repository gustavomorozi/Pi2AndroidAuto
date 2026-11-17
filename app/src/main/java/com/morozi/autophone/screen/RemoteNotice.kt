package com.morozi.autophone.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.widget.Toast

/**
 * What the user sees while the Bluetooth remote is paired again in the
 * background: "Conectando controle…", then "Controle conectado" (or that it
 * did not work). A notification plus a short toast, since Android TV does not
 * always show notifications on top.
 */
internal object RemoteNotice {
    private const val CHANNEL = "remote"
    private const val ID = 7678

    fun connecting(context: Context, name: String) =
        show(context, "Conectando controle…", "$name voltou; pareando de novo", ongoing = true)

    fun connected(context: Context, name: String) =
        show(context, "Controle conectado", name, ongoing = false)

    fun failed(context: Context, name: String) =
        show(context, "Controle não conectou", "Aperte uma tecla do $name para tentar de novo", ongoing = false)

    private fun show(context: Context, title: String, text: String, ongoing: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Controle remoto", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { if (!ongoing) setTimeoutAfter(10_000) }
            .build()
        runCatching { manager.notify(ID, notification) }
        Toast.makeText(context, title, Toast.LENGTH_LONG).show()
    }
}
