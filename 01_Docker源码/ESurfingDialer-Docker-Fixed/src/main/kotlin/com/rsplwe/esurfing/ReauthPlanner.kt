package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AuthorizationTrigger {
    INITIAL,
    PORTAL,
    SCHEDULED
}

data class ReauthPlanSnapshot(
    val enabled: Boolean,
    val estimatedLifetimeSeconds: Long,
    val lastAuthSuccessAt: Long,
    val nextPlannedReauthAt: Long,
    val lastObservedLifetimeSeconds: Long,
    val successfulAuthSamples: Int,
    val safeWindowStartHour: Int,
    val safeWindowEndHour: Int,
)

object ReauthPlanner {
    private val logger: Logger = Logger.getLogger(ReauthPlanner::class.java)
    private val zoneId: ZoneId = ZoneId.of("+8")
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val stateFile: File get() = File(States.rootDir, "reauth-plan.json")

    private const val DEFAULT_INTERVAL_SECONDS = 48L * 60L * 60L
    private const val MAX_INTERVAL_SECONDS = 7L * 24L * 60L * 60L
    private const val DEFAULT_LEAD_SECONDS = 90L * 60L

    @Volatile private var loaded = false
    @Volatile private var estimatedLifetimeSeconds: Long = DEFAULT_INTERVAL_SECONDS
    @Volatile private var lastAuthSuccessAt: Long = 0
    @Volatile private var nextPlannedReauthAt: Long = 0
    @Volatile private var lastObservedLifetimeSeconds: Long = 0
    @Volatile private var successfulAuthSamples: Int = 0

    fun recordAuthSuccess(trigger: AuthorizationTrigger, nowSeconds: Long = nowSeconds()) {
        ensureLoaded()

        val previousAuthAt = lastAuthSuccessAt
        if (trigger != AuthorizationTrigger.SCHEDULED && previousAuthAt > 0 && nowSeconds > previousAuthAt) {
            val observedLifetime = nowSeconds - previousAuthAt
            if (observedLifetime in 3600L..MAX_INTERVAL_SECONDS) {
                val blended = ((estimatedLifetimeSeconds * 3) + observedLifetime) / 4
                estimatedLifetimeSeconds = blended.coerceIn(3600L, MAX_INTERVAL_SECONDS)
                lastObservedLifetimeSeconds = observedLifetime
                successfulAuthSamples += 1
                logger.info(
                    "REAUTH_SAMPLE_ACCEPTED trigger=$trigger observed=${observedLifetime}s " +
                        "estimated=${estimatedLifetimeSeconds}s samples=$successfulAuthSamples"
                )
            } else {
                logger.warn("REAUTH_SAMPLE_IGNORED trigger=$trigger observed=${observedLifetime}s")
            }
        }

        lastAuthSuccessAt = nowSeconds
        nextPlannedReauthAt = computeNextPlannedReauthAt(nowSeconds, estimatedLifetimeSeconds)
        persist()
        logger.info(
            "REAUTH_PLAN_UPDATED trigger=$trigger nextAt=${formatEpoch(nextPlannedReauthAt)} " +
                "estimated=${estimatedLifetimeSeconds}s safeWindow=${safeWindowLabel()}"
        )
    }

    fun shouldTriggerScheduledReauth(nowSeconds: Long = nowSeconds()): Boolean {
        ensureLoaded()
        if (!RuntimeConfig.autoReauthEnabled) return false
        if (nextPlannedReauthAt <= 0 || nowSeconds < nextPlannedReauthAt) return false
        return isInSafeWindow(nowSeconds) || overdueBeyondGrace(nowSeconds)
    }

    fun isWaitingForSafeWindow(nowSeconds: Long = nowSeconds()): Boolean {
        ensureLoaded()
        if (!RuntimeConfig.autoReauthEnabled) return false
        if (nextPlannedReauthAt <= 0 || nowSeconds < nextPlannedReauthAt) return false
        return !isInSafeWindow(nowSeconds) && !overdueBeyondGrace(nowSeconds)
    }

    fun shouldLogWaiting(nowSeconds: Long = nowSeconds()): Boolean {
        return isWaitingForSafeWindow(nowSeconds)
    }

    fun snapshot(): ReauthPlanSnapshot {
        ensureLoaded()
        return ReauthPlanSnapshot(
            enabled = RuntimeConfig.autoReauthEnabled,
            estimatedLifetimeSeconds = estimatedLifetimeSeconds,
            lastAuthSuccessAt = lastAuthSuccessAt,
            nextPlannedReauthAt = nextPlannedReauthAt,
            lastObservedLifetimeSeconds = lastObservedLifetimeSeconds,
            successfulAuthSamples = successfulAuthSamples,
            safeWindowStartHour = RuntimeConfig.autoReauthSafeWindowStartHour,
            safeWindowEndHour = RuntimeConfig.autoReauthSafeWindowEndHour,
        )
    }

    fun safeWindowLabel(): String =
        String.format(
            "%02d:00-%02d:00",
            RuntimeConfig.autoReauthSafeWindowStartHour,
            RuntimeConfig.autoReauthSafeWindowEndHour
        )

    fun formatEpoch(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "-"
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zoneId).format(formatter)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadState()
            loaded = true
        }
    }

    private fun loadState() {
        val file = stateFile
        if (!file.exists()) {
            return
        }

        runCatching {
            val content = file.readText()
            estimatedLifetimeSeconds = extractLong(content, "estimatedLifetimeSeconds", DEFAULT_INTERVAL_SECONDS)
                .coerceIn(3600L, MAX_INTERVAL_SECONDS)
            lastAuthSuccessAt = extractLong(content, "lastAuthSuccessAt", 0)
            nextPlannedReauthAt = extractLong(content, "nextPlannedReauthAt", 0)
            lastObservedLifetimeSeconds = extractLong(content, "lastObservedLifetimeSeconds", 0)
            successfulAuthSamples = extractInt(content, "successfulAuthSamples", 0)
        }.onFailure { error ->
            logger.warn("Failed to load reauth planner state: ${error.message}")
        }
    }

    private fun persist() {
        runCatching {
            val file = stateFile
            file.parentFile?.mkdirs()
            file.writeText(
                """
                {
                  "estimatedLifetimeSeconds": $estimatedLifetimeSeconds,
                  "lastAuthSuccessAt": $lastAuthSuccessAt,
                  "nextPlannedReauthAt": $nextPlannedReauthAt,
                  "lastObservedLifetimeSeconds": $lastObservedLifetimeSeconds,
                  "successfulAuthSamples": $successfulAuthSamples,
                  "updatedAt": ${nowSeconds()}
                }
                """.trimIndent() + "\n"
            )
        }.onFailure { error ->
            logger.warn("Failed to persist reauth planner state: ${error.message}")
        }
    }

    private fun computeNextPlannedReauthAt(authSuccessAt: Long, currentEstimateSeconds: Long): Long {
        val proactiveTarget = authSuccessAt + currentEstimateSeconds - DEFAULT_LEAD_SECONDS
        val safeAlignedTarget = alignToSafeWindowBefore(proactiveTarget)
        return if (safeAlignedTarget > authSuccessAt + DEFAULT_LEAD_SECONDS) {
            safeAlignedTarget
        } else {
            proactiveTarget
        }
    }

    private fun alignToSafeWindowBefore(targetSeconds: Long): Long {
        val windowStart = RuntimeConfig.autoReauthSafeWindowStartHour.coerceIn(0, 23)
        val windowEnd = RuntimeConfig.autoReauthSafeWindowEndHour.coerceIn(0, 24)
        if (windowStart == windowEnd) return targetSeconds

        val targetDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(targetSeconds), zoneId)
        val targetMinutes = targetDateTime.hour * 60 + targetDateTime.minute
        val startMinutes = windowStart * 60
        val endMinutes = windowEnd * 60

        if (isMinutesInWindow(targetMinutes, startMinutes, endMinutes)) {
            return targetSeconds
        }

        val windowDate = if (isAfterWindow(targetMinutes, startMinutes, endMinutes)) {
            targetDateTime.toLocalDate()
        } else {
            targetDateTime.toLocalDate().minusDays(1)
        }

        val windowStartDateTime = windowDate.atTime(windowStart, 0)
        return windowStartDateTime.atZone(zoneId).toEpochSecond()
    }

    private fun isInSafeWindow(nowSeconds: Long): Boolean {
        val now = LocalDateTime.ofInstant(Instant.ofEpochSecond(nowSeconds), zoneId)
        val currentMinutes = now.hour * 60 + now.minute
        val startMinutes = RuntimeConfig.autoReauthSafeWindowStartHour.coerceIn(0, 23) * 60
        val endMinutes = RuntimeConfig.autoReauthSafeWindowEndHour.coerceIn(0, 24) * 60
        return isMinutesInWindow(currentMinutes, startMinutes, endMinutes)
    }

    private fun overdueBeyondGrace(nowSeconds: Long): Boolean {
        if (nextPlannedReauthAt <= 0) return false
        val windowSeconds = safeWindowDurationSeconds()
        return nowSeconds - nextPlannedReauthAt >= windowSeconds
    }

    private fun safeWindowDurationSeconds(): Long {
        val start = RuntimeConfig.autoReauthSafeWindowStartHour.coerceIn(0, 23)
        val end = RuntimeConfig.autoReauthSafeWindowEndHour.coerceIn(0, 24)
        return if (end >= start) {
            (end - start).toLong() * 60L * 60L
        } else {
            ((24 - start) + end).toLong() * 60L * 60L
        }
    }

    private fun isMinutesInWindow(minutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        return if (endMinutes > startMinutes) {
            minutes in startMinutes until endMinutes
        } else if (endMinutes < startMinutes) {
            minutes >= startMinutes || minutes < endMinutes
        } else {
            true
        }
    }

    private fun isAfterWindow(minutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        return if (endMinutes > startMinutes) {
            minutes >= endMinutes
        } else if (endMinutes < startMinutes) {
            minutes in endMinutes until startMinutes
        } else {
            true
        }
    }

    private fun extractLong(content: String, field: String, defaultValue: Long): Long {
        val match = Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(content) ?: return defaultValue
        return match.groupValues.getOrNull(1)?.toLongOrNull() ?: defaultValue
    }

    private fun extractInt(content: String, field: String, defaultValue: Int): Int {
        val match = Regex("\"$field\"\\s*:\\s*(-?\\d+)").find(content) ?: return defaultValue
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: defaultValue
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
