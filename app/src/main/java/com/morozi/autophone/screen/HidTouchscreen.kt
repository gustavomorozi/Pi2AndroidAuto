package com.morozi.autophone.screen

import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "HidTouchscreen"

/**
 * A virtual touchscreen made with the kernel's uhid interface (root only):
 * Android sees a real HID digitizer, so a touch from the car works in any app
 * like a finger on a screen. The CarlinKit phone role injects car touches the
 * same way (virtual HID touchscreens).
 *
 * One contact (tap, drag, scroll). Coordinates are sent in 0..[MAX] and Android
 * maps that range onto the main display.
 */
internal class HidTouchscreen private constructor(private val uhid: FileOutputStream) {

    @Synchronized
    fun down(x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        report(true, x * MAX / maxOf(screenWidth - 1, 1), y * MAX / maxOf(screenHeight - 1, 1))
    }

    @Synchronized
    fun up() {
        report(false, lastX, lastY)
    }

    private var lastX = 0
    private var lastY = 0

    private fun report(touching: Boolean, x: Int, y: Int) {
        lastX = x.coerceIn(0, MAX)
        lastY = y.coerceIn(0, MAX)
        val data = byteArrayOf(
            REPORT_ID.toByte(), if (touching) 1 else 0,
            (lastX and 0xFF).toByte(), (lastX shr 8).toByte(),
            (lastY and 0xFF).toByte(), (lastY shr 8).toByte()
        )
        // UHID_INPUT2: u32 type, u16 size, data
        val event = ByteBuffer.allocate(4 + 2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(UHID_INPUT2).putShort(data.size.toShort()).put(data).array()
        uhid.write(event)
    }

    companion object {
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val BUS_VIRTUAL = 0x06
        private const val REPORT_ID = 1
        const val MAX = 32767

        /**
         * Report descriptor: Digitizer / Touch Screen, one finger with Tip
         * Switch and absolute X/Y (16 bit, 0..32767). Report = id, tip, x, y.
         */
        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x0D,                   // Usage Page (Digitizer)
            0x09, 0x04,                   // Usage (Touch Screen)
            0xA1.toByte(), 0x01,          // Collection (Application)
            0x85.toByte(), REPORT_ID.toByte(), //   Report ID
            0x09, 0x22,                   //   Usage (Finger)
            0xA1.toByte(), 0x00,          //   Collection (Physical)
            0x09, 0x42,                   //     Usage (Tip Switch)
            0x15, 0x00, 0x25, 0x01,       //     Logical 0..1
            0x75, 0x01, 0x95.toByte(), 0x01, //  1 bit
            0x81.toByte(), 0x02,          //     Input (Data, Var, Abs)
            0x75, 0x07, 0x95.toByte(), 0x01, //  7 bit padding
            0x81.toByte(), 0x03,          //     Input (Const)
            0x05, 0x01,                   //     Usage Page (Generic Desktop)
            0x09, 0x30,                   //     Usage (X)
            0x15, 0x00, 0x26, 0xFF.toByte(), 0x7F, // Logical 0..32767
            0x75, 0x10, 0x95.toByte(), 0x01, //  16 bit
            0x81.toByte(), 0x02,          //     Input (Data, Var, Abs)
            0x09, 0x31,                   //     Usage (Y)
            0x81.toByte(), 0x02,          //     Input (Data, Var, Abs)
            0xC0.toByte(),                //   End Collection
            0xC0.toByte()                 // End Collection
        )

        fun create(): HidTouchscreen {
            val out = FileOutputStream("/dev/uhid")
            // UHID_CREATE2: u32 type, name[128], phys[64], uniq[64], u16 rd_size,
            // u16 bus, u32 vendor, u32 product, u32 version, u32 country, rd_data[4096]
            val event = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + 4096).order(ByteOrder.LITTLE_ENDIAN)
            event.putInt(UHID_CREATE2)
            event.put(fixed("Morozi Car Touchscreen", 128))
            event.put(fixed("morozi/car", 64))
            event.put(fixed("", 64))
            event.putShort(DESCRIPTOR.size.toShort())
            event.putShort(BUS_VIRTUAL.toShort())
            event.putInt(0x18D1).putInt(0x7A01).putInt(1).putInt(0)
            event.put(DESCRIPTOR)
            out.write(event.array())
            Log.i(TAG, "virtual touchscreen created")
            return HidTouchscreen(out)
        }

        private fun fixed(text: String, size: Int): ByteArray = text.toByteArray().copyOf(size)
    }
}
