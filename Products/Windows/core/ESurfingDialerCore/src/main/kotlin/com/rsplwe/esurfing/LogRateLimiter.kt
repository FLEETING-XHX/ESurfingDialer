package com.rsplwe.esurfing

class LogRateLimiter(
    private val intervalMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastLoggedAt = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldLog(key: String): Boolean {
        val now = clock()
        val previous = lastLoggedAt[key]
        if (previous != null && now - previous < intervalMillis) return false
        lastLoggedAt[key] = now
        return true
    }

    @Synchronized
    fun reset(key: String) {
        lastLoggedAt.remove(key)
    }
}
