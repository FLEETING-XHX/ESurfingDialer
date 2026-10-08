package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.util.concurrent.TimeUnit

internal class HeartbeatLog(
    private val logger: Logger,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private var lastReportAt: Long? = null
    private var successesSinceReport = 0

    @Synchronized fun reset() {
        lastReportAt = null
        successesSinceReport = 0
    }

    fun attempt() {
        logger.debug("HEARTBEAT_ATTEMPT")
    }

    @Synchronized fun success(previousFailures: Int, nextRetrySeconds: Long) {
        logger.debug("HEARTBEAT_SUCCESS nextRetry=${nextRetrySeconds}s")
        successesSinceReport++
        val now = monotonicNanos()
        val last = lastReportAt
        val message = when {
            previousFailures > 0 -> "HEARTBEAT_RECOVERED failures=$previousFailures nextRetry=${nextRetrySeconds}s"
            last == null || now - last >= TimeUnit.MINUTES.toNanos(30) ->
                "HEARTBEAT_SUMMARY successes=$successesSinceReport nextRetry=${nextRetrySeconds}s"
            else -> return
        }
        logger.info(message)
        lastReportAt = now
        successesSinceReport = 0
    }
}
