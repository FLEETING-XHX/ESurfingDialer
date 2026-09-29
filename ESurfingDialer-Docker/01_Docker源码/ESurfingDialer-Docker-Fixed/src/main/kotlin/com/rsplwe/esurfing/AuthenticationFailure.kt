package com.rsplwe.esurfing

class AuthenticationFailure(val code: String, val deterministic: Boolean = false,
                            val portalDiagnostics: PortalDiagnostics? = null) : IllegalStateException(code)

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
