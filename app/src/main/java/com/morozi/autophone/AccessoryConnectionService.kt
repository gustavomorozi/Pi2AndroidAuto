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
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

private const val TAG = "AccessoryConnection"

/** Larger than any single USB transfer the car sends. */
private const val ACCESSORY_READ_BUFFER = 16384

/** Empty reads in a row (~10 s) before we treat the link as gone. */
private const val MAX_EMPTY_READS = 500
private const val EMPTY_READ_PAUSE_MS = 20L

/** How long to wait for the old USB reader to exit before reopening. */
private const val READER_EXIT_WAIT_MS = 1000L

/** Starts closer together than this are one attach reported twice. */
private const val DUPLICATE_START_WINDOW_MS = 1500L

/** Room for what the car sends while the session is busy elsewhere. */
private const val ACCESSORY_PIPE_BUFFER = 1 shl 20

/** Reopen attempts after a link drop while the car still has us attached. */
private const val MAX_REOPEN_ATTEMPTS = 3
private const val REOPEN_DELAY_MS = 1500L
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
    private var lastOpenAt = 0L
    private var readerThread: Thread? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reopenAttempts = 0

    /** Where every USB reader delivers what it reads: the newest session's pipe. */
    @Volatile private var currentSink: java.io.PipedOutputStream? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accessory: UsbAccessory? = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        if (accessory == null) {
            Log.w(TAG, "Started without an accessory extra; stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Conectado: ${accessory.model}"))
        // One attach reaches us twice (the system's attach intent and our own
        // pick-up of an already attached accessory, milliseconds apart); opening
        // again would close the session that just started.
        val now = android.os.SystemClock.elapsedRealtime()
        if (session != null && now - lastOpenAt < DUPLICATE_START_WINDOW_MS) {
            Log.i(TAG, "Ignoring duplicate start for the accessory just opened")
            return START_NOT_STICKY
        }
        lastOpenAt = now
        reopenAttempts = 0  // a fresh attach from the car
        openAccessory(accessory)
        return START_NOT_STICKY
    }

    private fun openAccessory(accessory: UsbAccessory) {
        // A new attach while an old session still holds the previous fd: that
        // old session would read the car's new messages and then fail writing
        // (seen with the CarlinKit adapter). Drop it before opening again.
        closeCurrent()
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val fd = usbManager.openAccessory(accessory)
        if (fd == null) {
            Log.e(TAG, "openAccessory() returned null for ${accessory.model}")
            stopSelf()
            return
        }
        accessoryFd = fd

        Log.i(TAG, "Accessory opened: ${accessory.manufacturer} / ${accessory.model}; waiting for the car")
        // Each read on an accessory fd consumes one whole USB transfer and drops
        // whatever doesn't fit, so always read with a buffer larger than any
        // transfer (seen in the car: a 1-byte read lost the rest of the frame).
        // A dedicated thread keeps a read posted at all times and queues what
        // arrives, so the car never finds us not reading (while we load keys,
        // encode video, etc.) and gives up on a transfer.
        val raw = RawLogInputStream(FileInputStream(fd.fileDescriptor))
        val pipeIn = java.io.PipedInputStream(ACCESSORY_PIPE_BUFFER)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        currentSink = pipeOut
        readerThread = thread(name = "aap-usb-reader") {
            val buffer = ByteArray(ACCESSORY_READ_BUFFER)
            try {
                var emptyReads = 0
                while (true) {
                    val n = raw.read(buffer, 0, buffer.size)
                    if (n <= 0) {
                        // An accessory fd reports "no data" right after opening
                        // and on zero-length packets; only a real error (an
                        // exception) means the car went away.
                        if (++emptyReads > MAX_EMPTY_READS) break
                        Thread.sleep(EMPTY_READ_PAUSE_MS)
                        continue
                    }
                    emptyReads = 0
                    // A reader left over from an earlier session can stay blocked
                    // in read() on the old fd after it was closed, and the kernel
                    // then hands it the car's next transfer. That stole the car's
                    // TLS ClientHello right after our version answer (the
                    // "silence after version" seen with the CarlinKit adapter and
                    // the DHU). Whatever any reader gets belongs to the current
                    // session, so always deliver to the latest sink.
                    val sink = currentSink ?: pipeOut
                    if (sink !== pipeOut) Log.i(TAG, "Earlier USB reader got $n bytes; passing them to the current session")
                    sink.write(buffer, 0, n)
                    // Without flush the piped reader only wakes on its 1 s poll:
                    // our version answer went out a second late and the CarlinKit
                    // adapter stopped before TLS.
                    sink.flush()
                }
            } catch (e: Exception) {
                Log.i(TAG, "USB reader stopped: ${e.message}")
            } finally {
                runCatching { pipeOut.close() }
            }
        }
        val input = BufferedInputStream(pipeIn, ACCESSORY_READ_BUFFER)
        val output = FileOutputStream(fd.fileDescriptor)

        lateinit var aapSession: AapSession
        aapSession = AapSession(input, output, maxVideoPixels = Settings(this).maxVideoPixels, videoQuality = Settings(this).videoQuality) { reason ->
            Log.i(TAG, "Session ended: $reason")
            // Only the current session may end the service; a replaced one just goes away.
            if (session === aapSession) {
                closeCurrent()
                retryOrGiveUp(accessory)
            }
        }
        session = aapSession

        sessionThread = thread(name = "aap-session") {
            aapSession.run()
        }
    }

    @Synchronized
    private fun closeCurrent() {
        if (accessoryFd != null) Log.i(TAG, "Closing accessory fd")
        session?.shutdown()
        session = null
        try {
            accessoryFd?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing accessory fd failed", e)
        }
        accessoryFd = null
        // Let the old fd's release finish in the driver before a new open:
        // reopening while it was still closing killed the new session at once.
        val reader = readerThread
        readerThread = null
        if (reader != null && reader !== Thread.currentThread()) reader.join(READER_EXIT_WAIT_MS)
    }

    /**
     * A link that drops while the car still has us attached usually comes back
     * if we simply open the accessory again (an approach that works on real
     * head units for other phone-side implementations). Try a few times; only
     * then give up, which the Pi's morozi_aoawatch helper answers by taking us
     * off the bus so the car starts over from a clean AOA switch.
     */
    private fun retryOrGiveUp(accessory: UsbAccessory) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val stillAttached = usbManager.accessoryList?.any { it == accessory } == true
        if (stillAttached && reopenAttempts < MAX_REOPEN_ATTEMPTS) {
            reopenAttempts++
            Log.i(TAG, "Link dropped but the accessory is still attached; reopening in ${REOPEN_DELAY_MS} ms (try $reopenAttempts/$MAX_REOPEN_ATTEMPTS)")
            mainHandler.postDelayed({ if (session == null) openAccessory(accessory) }, REOPEN_DELAY_MS)
        } else {
            Log.i(TAG, "USB session gave up (attached=$stillAttached, tries=$reopenAttempts)")
            reopenAttempts = 0
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCurrent()
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
