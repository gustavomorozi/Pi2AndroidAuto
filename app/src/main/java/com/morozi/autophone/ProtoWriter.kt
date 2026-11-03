package com.morozi.autophone

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf encoder for the few message bodies we send. Only the wire
 * types we need: varint (0) and length-delimited (2).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        writeVarint((field.toLong() shl 3) or 0)
        writeVarint(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        writeVarint((field.toLong() shl 3) or 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
