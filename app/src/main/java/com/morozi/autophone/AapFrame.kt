package com.morozi.autophone

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * One frame of the Android Auto wire protocol: a small header (which logical
 * channel this belongs to, a few flag bits, and a length) followed by that many
 * bytes of payload. This is the common framing structure shared by every
 * independent open implementation of the protocol (DHU, openauto/aasdk,
 * Headunit/Open Headunit, aa-proxy-rs) -- it is how the protocol itself is
 * shaped, not anything specific to one product. The exact flag combinations
 * below are what we could confirm empirically against our own Open Headunit
 * instance; treat them as a starting point to validate, not gospel.
 */
data class AapFrame(
    val channelId: Int,
    val flags: Int,
    val payload: ByteArray
) {
    val isControl: Boolean get() = (flags and FLAG_CONTROL) != 0
    val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
    val isFirstChunk: Boolean get() = (flags and FLAG_FIRST_CHUNK) != 0
    val isLastChunk: Boolean get() = (flags and FLAG_LAST_CHUNK) != 0

    companion object {
        // Observed on the wire: Open Headunit's VERSION_REQUEST arrives as
        // 00 03 00 06 ..., i.e. an unfragmented frame carries 0x03. The first
        // and last bits therefore sit at 0x01 and 0x02; the control and
        // encrypted bits are still to be confirmed against live traffic.
        const val FLAG_FIRST_CHUNK = 0x01
        const val FLAG_LAST_CHUNK = 0x02
        const val FLAG_CONTROL = 0x04
        const val FLAG_ENCRYPTED = 0x08

        /** A whole message in one frame. */
        const val FLAG_UNFRAGMENTED = FLAG_FIRST_CHUNK or FLAG_LAST_CHUNK
    }
}

/** Reads [AapFrame]s off the raw accessory input stream. Not thread-safe. */
class AapFrameReader(rawInput: InputStream) {
    private val input = DataInputStream(rawInput)

    fun readFrame(): AapFrame {
        val channelId = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val shortLength = input.readUnsignedShort()

        // Messages too large for a 16-bit length arrive as a short length of
        // 0xFFFF followed by a real 32-bit length -- this mirrors how every
        // independent implementation handles oversized (e.g. video) frames.
        val length = if (shortLength == 0xFFFF) input.readInt() else shortLength

        val payload = ByteArray(length)
        input.readFully(payload)
        return AapFrame(channelId, flags, payload)
    }
}

/** Writes [AapFrame]s to the raw accessory output stream. Not thread-safe. */
class AapFrameWriter(rawOutput: OutputStream) {
    private val output = DataOutputStream(rawOutput)

    @Synchronized
    fun writeFrame(frame: AapFrame) {
        output.writeByte(frame.channelId)
        output.writeByte(frame.flags)
        if (frame.payload.size < 0xFFFF) {
            output.writeShort(frame.payload.size)
        } else {
            output.writeShort(0xFFFF)
            output.writeInt(frame.payload.size)
        }
        output.write(frame.payload)
        output.flush()
    }
}
