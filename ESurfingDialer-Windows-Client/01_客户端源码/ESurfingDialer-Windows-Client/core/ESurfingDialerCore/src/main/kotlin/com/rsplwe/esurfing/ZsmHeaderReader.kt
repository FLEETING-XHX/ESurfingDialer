package com.rsplwe.esurfing

import java.util.Locale

/** Read metadata only. Packed-module markers and UUIDs are candidates, not verified algorithms. */
object ZsmHeaderReader {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun inspect(bytes: ByteArray): ZsmMetadata {
        if (bytes.isEmpty()) throw AuthenticationFailure("ZSM_EMPTY", true)
        if (bytes.size > AuthenticationDiagnostics.MAX_ZSM_BYTES) throw AuthenticationFailure("ZSM_TOO_LARGE", true)
        val prefix = bytes.take(128).toByteArray().toString(Charsets.US_ASCII).trimStart().lowercase(Locale.ROOT)
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<?xml")
            || prefix.startsWith("{\"") || prefix.startsWith("<error"))
            throw AuthenticationFailure("ZSM_UNEXPECTED_TEXT", true)
        if (bytes.size < 5) throw AuthenticationFailure("ZSM_TRUNCATED", true)
        var offset = 3
        fun field(): String? {
            if (offset >= bytes.size) return null
            val length = bytes[offset++].toInt() and 0xff
            if (length > bytes.size - offset) return null
            val value = bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
            offset += length
            return value
        }
        val first = field()
        val second = if (first != null) field() else null
        val id = listOfNotNull(second, first).firstOrNull { uuid.matches(it) }?.uppercase(Locale.ROOT)
        if (first == null || second == null) return ZsmMetadata("unknown", id)
        // The local C attachment observes a packed size/type word after the two strings and five bytes.
        // Inspect that fixed header only; never decompress, execute, or scan arbitrary payload for a UUID.
        if (bytes.size - offset >= 9) {
            var packed = 0L
            repeat(4) { index -> packed = packed or ((bytes[offset + 5 + index].toLong() and 0xff) shl (index * 8)) }
            val type = (packed ushr 28).toInt()
            val size = (packed and 0x0fffffff).toInt()
            if (type == 2 && size in 1..0x08000000)
                return ZsmMetadata("packed_dynamic_candidate", id, offset, size)
        }
        return ZsmMetadata("length_prefixed", id, offset)
    }
}
