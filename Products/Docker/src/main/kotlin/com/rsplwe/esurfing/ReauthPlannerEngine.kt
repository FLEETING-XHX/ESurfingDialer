package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** One persisted daily claim, independent of heartbeat recovery and session lifetime. */
class ReauthPlannerEngine(
    private val file: File,
    private val enabled: Boolean,
    private val startHour: Int,
    private val endHour: Int,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
    @Suppress("UNUSED_PARAMETER") monotonic: () -> Long = System::nanoTime,
    private val writer: (File, String) -> Unit = StateFiles::write,
) {
    companion object { const val DEFAULT_INTERVAL = 86400L }
    private val logger = Logger.getLogger(ReauthPlannerEngine::class.java)
    private val zone = ZoneOffset.ofHours(8)
    private var handledDay = -1L
    private var lastAuth = 0L
    private var loaded = false
    private var loadFailed = false

    init { require(startHour in 0..23 && endHour in startHour..24) { "结束时间不能早于起始时间" } }

    // Legacy callers retain these hooks; samples no longer affect the daily schedule.
    fun invalidateSample() = Unit
    fun observePortal(@Suppress("UNUSED_PARAMETER") key: String?) = Unit
    fun recordHeartbeatFailure(@Suppress("UNUSED_PARAMETER") protocolRejected: Boolean) = Unit

    private fun day(now: Long) = Instant.ofEpochSecond(now).atOffset(zone).toLocalDate()
    private fun start(now: Long) = day(now).atStartOfDay().atOffset(zone).toEpochSecond() + startHour * 3600L
    private fun end(now: Long) = if (startHour == endHour) start(now) + 60L
        else day(now).atStartOfDay().atOffset(zone).toEpochSecond() + endHour * 3600L
    internal fun isInWindow(now: Long) = now >= start(now) && now < end(now)

    private fun next(now: Long): Long {
        if (!enabled || loadFailed) return 0L
        val today = day(now).toEpochDay()
        val targetDay = maxOf(today, handledDay + 1)
        if (targetDay > today) return java.time.LocalDate.ofEpochDay(targetDay).atTime(startHour, 0).atOffset(zone).toEpochSecond()
        return if (now < end(now)) start(now) else start(now) + 86400L
    }

    @Synchronized fun recordAuthSuccess(trigger: AuthorizationTrigger, now: Long = clock(), @Suppress("UNUSED_PARAMETER") networkKey: String? = null) {
        ensureLoaded(now)
        lastAuth = now
        if (!loadFailed && isInWindow(now)) {
            val date = day(now).toEpochDay()
            if (date > handledDay && persist(date, now)) handledDay = date
        } else if (!loadFailed) persist(handledDay, now)
        logger.info("DAILY_REAUTH_PLAN_UPDATED trigger=$trigger nextAt=${next(now)}")
    }

    /** Pure due check for callers that must first obtain safe portal context. */
    @Synchronized fun isDue(now: Long = clock()): Boolean {
        ensureLoaded(now)
        return enabled && !loadFailed && day(now).toEpochDay() > handledDay && isInWindow(now)
    }

    /** Persist BEFORE authorizing; failed persistence never starts a daily operation. */
    @Synchronized fun shouldTrigger(now: Long = clock()): Boolean {
        if (!isDue(now)) return false
        val date = day(now).toEpochDay()
        if (!persist(date, now)) return false
        handledDay = date
        return true
    }

    @Synchronized fun isWaiting(now: Long = clock()): Boolean { ensureLoaded(now); return enabled && next(now) > now }

    @Synchronized fun snapshot(): ReauthPlanSnapshot {
        val now = clock(); ensureLoaded(now)
        return ReauthPlanSnapshot(enabled, DEFAULT_INTERVAL, lastAuth, next(now), 0, 0, startHour, endHour,
            "daily", handledDay, loadFailed)
    }

    private fun ensureLoaded(now: Long) {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            val values = StateJson.decode(StateFiles.read(file))
            val schema = values["schemaVersion"] as? Long ?: 1L
            if (schema !in 1L..3L) {
                loadFailed = true
                logger.error("DAILY_REAUTH_SCHEMA_UNKNOWN scheduled reauthentication paused; ordinary recovery remains enabled")
                return
            }
            if (schema == 3L) {
                handledDay = values["handledDay"] as? Long ?: error("DAILY_STATE_INVALID")
                require(handledDay in -1L..2932896L)
                lastAuth = values["lastAuthSuccessAt"] as? Long ?: error("DAILY_STATE_INVALID")
            } else {
                // Preserve an in-window successful login; discard old prediction/learned periods.
                lastAuth = values["lastAuthSuccessAt"] as? Long ?: error("DAILY_STATE_INVALID")
                if (lastAuth in 1..now && isInWindow(lastAuth)) handledDay = day(lastAuth).toEpochDay()
            }
            require(lastAuth in 0..253402300799L)
        } catch (_: Exception) {
            // Unreadable history must not cause another logout today. Repair a known schema
            // conservatively so a transient bad file does not disable daily scheduling forever.
            handledDay = day(now).toEpochDay()
            lastAuth = 0L
            loadFailed = !persist(handledDay, now)
            logger.error("DAILY_REAUTH_STATE_INVALID skipping today's scheduled operation; ordinary recovery remains enabled")
        }
    }

    private fun persist(date: Long, now: Long): Boolean = try {
        writer(file, StateJson.encode(linkedMapOf("schemaVersion" to 3, "mode" to "daily",
            "handledDay" to date, "lastAuthSuccessAt" to lastAuth, "nextPlannedReauthAt" to next(now), "updatedAt" to now)))
        true
    } catch (_: Exception) {
        logger.error("DAILY_REAUTH_STATE_WRITE_FAILED scheduled operation deferred")
        false
    }
}
