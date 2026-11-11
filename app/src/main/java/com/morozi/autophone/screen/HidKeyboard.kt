package com.morozi.autophone.screen

import android.util.Log
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "HidKeyboard"

/**
 * A virtual keyboard made with uhid, for the arrow keys and OK that remote-only
 * TV apps need ([TapAssistService]).
 *
 * `input keyevent` injects the key release only after the app has finished the
 * key press, and on a busy Pi that can take over half a second: the Google TV
 * launcher read it as a long press (the "Abrir / Mover / Remover dos favoritos"
 * menu). Here press and release are written back to back, so both reach Android
 * within a millisecond of each other.
 */
internal class HidKeyboard private constructor(private val uhid: FileOutputStream) {

    /** Presses and releases the Android key [androidCode] [repeat] times; false if it has no HID key here. */
    @Synchronized
    fun press(androidCode: Int, repeat: Int): Boolean {
        val usage = USAGE[androidCode] ?: return false
        repeat(repeat) { i ->
            if (i > 0) Thread.sleep(GAP_MS)
            report(usage)
            report(0)
        }
        return true
    }

    private fun report(usage: Int) {
        // Boot keyboard report: modifiers, reserved, six key slots.
        val data = byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0)
        val event = ByteBuffer.allocate(4 + 2 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(UHID_INPUT2).putShort(data.size.toShort()).put(data).array()
        uhid.write(event)
    }

    companion object {
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val BUS_VIRTUAL = 0x06
        private const val GAP_MS = 60L

        /** Android key code -> HID keyboard usage. */
        private val USAGE = mapOf(
            19 to 0x52, // DPAD_UP    -> Up Arrow
            20 to 0x51, // DPAD_DOWN  -> Down Arrow
            21 to 0x50, // DPAD_LEFT  -> Left Arrow
            22 to 0x4F, // DPAD_RIGHT -> Right Arrow
            23 to 0x28, // DPAD_CENTER -> Return (Android TV apps take it as OK)
            66 to 0x28, // ENTER
            4 to 0x29,  // BACK       -> Escape
        )

        /** Standard HID boot keyboard descriptor (8-byte report, no report id). */
        private val DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01,          // Generic Desktop, Keyboard, Application
            0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), // modifier keys
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02,                                  // 8 modifier bits
            0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x03, // reserved byte
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, // six key slots
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00,
            0xC0.toByte()
        )

        fun create(): HidKeyboard {
            val out = FileOutputStream("/dev/uhid")
            // Same UHID_CREATE2 layout as HidTouchscreen.
            val event = ByteBuffer.allocate(4 + 128 + 64 + 64 + 2 + 2 + 4 * 4 + 4096).order(ByteOrder.LITTLE_ENDIAN)
            event.putInt(UHID_CREATE2)
            event.put(fixed("Morozi Car Keys", 128))
            event.put(fixed("morozi/keys", 64))
            event.put(fixed("", 64))
            event.putShort(DESCRIPTOR.size.toShort())
            event.putShort(BUS_VIRTUAL.toShort())
            event.putInt(0x18D1).putInt(0x7A02).putInt(1).putInt(0)
            event.put(DESCRIPTOR)
            out.write(event.array())
            Log.i(TAG, "virtual keyboard created")
            return HidKeyboard(out)
        }

        private fun fixed(text: String, size: Int): ByteArray = text.toByteArray().copyOf(size)
    }
}
