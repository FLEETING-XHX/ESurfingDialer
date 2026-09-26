package com.rsplwe.esurfing

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Codes are safe to put in health.json; response bodies and exception messages are not. */
class AuthenticationFailure(val code: String, val deterministic: Boolean = false) :
    IllegalStateException(code)

data class ZsmMetadata(val format: String, val algoId: String? = null)

object AuthenticationDiagnostics {
    const val MAX_ZSM_BYTES = 1024 * 1024
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun endpoint(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "invalid"

    fun inspect(bytes: ByteArray): ZsmMetadata {
        if (bytes.isEmpty()) throw AuthenticationFailure("ZSM_EMPTY", true)
        if (bytes.size > MAX_ZSM_BYTES) throw AuthenticationFailure("ZSM_TOO_LARGE", true)
        val prefix = bytes.take(128).toByteArray().toString(Charsets.US_ASCII).trimStart().lowercase(Locale.ROOT)
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<?xml")
            || prefix.startsWith("{\"") || prefix.startsWith("<error"))
            throw AuthenticationFailure("ZSM_UNEXPECTED_TEXT", true)
        // The reference attachment uses two length-prefixed strings after three header bytes.
        // A UUID is only a diagnostic candidate; this does not prove algorithm support or validate keys.
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
        val second = field()
        val id = listOfNotNull(second, first).firstOrNull { uuid.matches(it) }?.uppercase(Locale.ROOT)
        // Unknown layouts remain eligible for the existing native provider. Never guess a UUID from arbitrary bytes.
        return ZsmMetadata(if (first != null && second != null) "length_prefixed" else "unknown", id)
    }
}

/** Pure boundary around native loading, so rejected handles and cleanup can be regression tested. */
object NativeSessionLoader {
    fun load(load: () -> Long, algorithm: (Long) -> String, free: (Long) -> Unit): Pair<Long, String> {
        val handle = try { load() } catch (_: Exception) { throw AuthenticationFailure("NATIVE_LOAD_EXCEPTION", true) }
        if (handle == 0L) throw AuthenticationFailure("NATIVE_SESSION_REJECTED", true)
        try {
            val id = algorithm(handle)
            if (!Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(id))
                throw AuthenticationFailure("NATIVE_ALGO_ID_INVALID", true)
            return handle to id
        } catch (_: Exception) {
            try { free(handle) } catch (_: Exception) { }
            throw AuthenticationFailure("NATIVE_ALGO_ID_INVALID", true)
        }
    }
}

class AuthenticationRetryPolicy(private val maximumFailures: Int = 3) {
    private var lastCode: String? = null
    private var consecutive = 0
    fun record(failure: AuthenticationFailure): Boolean {
        if (!failure.deterministic) { reset(); return false }
        consecutive = if (lastCode == failure.code) consecutive + 1 else 1
        lastCode = failure.code
        return consecutive >= maximumFailures
    }
    fun reset() { lastCode = null; consecutive = 0 }
}
