package com.rsplwe.esurfing

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Codes are safe to put in health.json; response bodies and exception messages are not. */
class AuthenticationFailure(val code: String, val deterministic: Boolean = false) :
    IllegalStateException(code)

data class ZsmMetadata(val format: String, val algoId: String? = null,
                       val payloadOffset: Int? = null, val declaredUnpackedBytes: Int? = null)

object AuthenticationDiagnostics {
    const val MAX_ZSM_BYTES = 1024 * 1024

    fun endpoint(url: String): String = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "invalid"

    fun inspect(bytes: ByteArray): ZsmMetadata = ZsmHeaderReader.inspect(bytes)

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
