package com.morozi.autophone

import android.util.Log
import java.io.FilterInputStream
import java.io.InputStream

private const val TAG = "AapRaw"
private const val MAX_LOGGED_BYTES = 48

/**
 * Passes reads straight through but logs the first bytes of every chunk the
 * host sends. The frame reader only reports once a whole frame has arrived, so
 * without this a host that sends something unexpected (or a partial frame)
 * looks identical to a host that sends nothing at all.
 */
class RawLogInputStream(source: InputStream) : FilterInputStream(source) {

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) Log.i(TAG, "rx 1 byte: ${"%02x".format(b)}")
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            val shown = minOf(count, MAX_LOGGED_BYTES)
            val hex = (0 until shown).joinToString(" ") { "%02x".format(buffer[offset + it]) }
            val suffix = if (count > shown) " ... (+${count - shown})" else ""
            Log.i(TAG, "rx $count bytes: $hex$suffix")
        }
        return count
    }
}
