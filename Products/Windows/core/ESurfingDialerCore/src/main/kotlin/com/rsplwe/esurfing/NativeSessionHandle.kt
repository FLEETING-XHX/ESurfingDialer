package com.rsplwe.esurfing

/** Serialize JNI use/free so shutdown cannot free an in-flight session or free it twice. */
class NativeSessionHandle(private val handle: Long, private val release: (Long) -> Unit) {
    private var closed = false

    @Synchronized fun <T> use(operation: (Long) -> T): T {
        if (closed) throw AuthenticationFailure("NATIVE_SESSION_CLOSED", true)
        return operation(handle)
    }

    @Synchronized fun free() {
        if (closed) return
        closed = true
        release(handle)
    }
}
