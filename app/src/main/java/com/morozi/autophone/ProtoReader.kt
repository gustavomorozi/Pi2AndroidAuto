package com.morozi.autophone

/**
 * Minimal protobuf decoder: walks the wire format without a schema and hands
 * back each field. Enough to pick the few values we need out of the car's
 * messages.
 */
class ProtoReader(private val data: ByteArray) {

    sealed class Field(val number: Int) {
        class Varint(number: Int, val value: Long) : Field(number)
        class Bytes(number: Int, val value: ByteArray) : Field(number) {
            fun message() = ProtoReader(value)
        }
        class Fixed(number: Int) : Field(number)
    }

    fun fields(): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < data.size) {
            val (key, afterKey) = varint(i)
            i = afterKey
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint(i).let { (v, next) -> out += Field.Varint(number, v); i = next }
                2 -> varint(i).let { (len, next) ->
                    val end = next + len.toInt()
                    out += Field.Bytes(number, data.copyOfRange(next, end))
                    i = end
                }
                1 -> { out += Field.Fixed(number); i += 8 }
                5 -> { out += Field.Fixed(number); i += 4 }
                else -> error("Unsupported wire type in field $number")
            }
        }
        return out
    }

    fun varints(number: Int): List<Long> =
        fields().filterIsInstance<Field.Varint>().filter { it.number == number }.map { it.value }

    fun messages(number: Int): List<ProtoReader> =
        fields().filterIsInstance<Field.Bytes>().filter { it.number == number }.map { it.message() }

    private fun varint(start: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var i = start
        while (true) {
            val b = data[i++].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return value to i
            shift += 7
        }
    }
}
