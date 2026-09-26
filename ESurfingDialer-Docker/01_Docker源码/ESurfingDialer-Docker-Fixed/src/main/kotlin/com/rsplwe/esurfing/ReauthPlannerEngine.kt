package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** The estimate is a conservative correlation, not proof of an operator's session policy. */
class ReauthPlannerEngine(
    private val file: File,
    private val enabled: Boolean,
    private val startHour: Int,
    private val endHour: Int,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
    private val monotonic: () -> Long = System::nanoTime,
    private val writer: (File, String) -> Unit = StateFiles::write,
) {
    companion object {
        const val DEFAULT_INTERVAL = 48L * 3600
        const val MAX_INTERVAL = 7L * 86400
        const val MIN_INTERVAL = 3600L
        const val MIN_REAUTH_GAP = 1800L
        private const val MAX_EPOCH = 253402300799L
    }
    private val logger = Logger.getLogger(ReauthPlannerEngine::class.java)
    private val zone = ZoneId.of("+8")
    private data class State(val estimate: Long = DEFAULT_INTERVAL, val lastAuth: Long = 0,
        val next: Long = 0, val observed: Long = 0, val samples: Int = 0)
    private var state = State()
    private var loaded = false
    private var lastWall = 0L
    private var baselineNanos = 0L
    private var baselineKey: String? = null
    private var eligible = false
    private var portalObserved = false
    private var heartbeatRejected = false

    init { require(startHour in 0..23 && endHour in 0..24) }

    @Synchronized fun invalidateSample() { eligible = false }
    @Synchronized fun observePortal(key: String?) {
        if (key == null || key != baselineKey) eligible = false
        else portalObserved = true
    }
    @Synchronized fun recordHeartbeatFailure(protocolRejected: Boolean) {
        if (protocolRejected) heartbeatRejected = true else eligible = false
    }

    @Synchronized fun recordAuthSuccess(trigger: AuthorizationTrigger, now: Long = clock(), networkKey: String? = null) {
        ensureLoaded(now)
        require(now in 1..(MAX_EPOCH - MAX_INTERVAL)) { "PLANNER_TIME_INVALID" }
        val observed = now - state.lastAuth
        val elapsed = (monotonic() - baselineNanos) / 1_000_000_000L
        val trusted = trigger == AuthorizationTrigger.PORTAL && eligible && portalObserved && heartbeatRejected &&
            baselineKey != null && networkKey == baselineKey && observed in MIN_INTERVAL..MAX_INTERVAL &&
            elapsed >= 0 && abs(elapsed - observed) <= 120
        if (trusted) {
            state = state.copy(estimate = ((state.estimate * 3 + observed) / 4).coerceIn(MIN_INTERVAL, MAX_INTERVAL),
                observed = observed, samples = (state.samples + 1).coerceAtMost(1_000_000))
            logger.info("REAUTH_CORRELATED_SAMPLE_ACCEPTED observed=${observed}s samples=${state.samples}")
        } else if (state.lastAuth > 0) logger.info("REAUTH_SAMPLE_IGNORED trigger=$trigger")
        state = state.copy(lastAuth = now, next = computeNext(now, state.estimate))
        baselineKey = networkKey
        baselineNanos = monotonic()
        eligible = networkKey != null
        portalObserved = false
        heartbeatRejected = false
        persist(now)
        logger.info("REAUTH_PLAN_UPDATED trigger=$trigger nextAt=${state.next} estimated=${state.estimate}s")
    }

    @Synchronized fun snapshot(): ReauthPlanSnapshot {
        ensureLoaded(clock())
        return ReauthPlanSnapshot(enabled, state.estimate, state.lastAuth, state.next, state.observed,
            state.samples, startHour, endHour)
    }
    @Synchronized fun shouldTrigger(now: Long = clock()): Boolean {
        ensureLoaded(now)
        return enabled && state.next > 0 && now >= state.next && (isInWindow(now) || overdue(now))
    }
    @Synchronized fun isWaiting(now: Long = clock()): Boolean {
        ensureLoaded(now)
        return enabled && state.next > 0 && now >= state.next && !isInWindow(now) && !overdue(now)
    }

    private fun ensureLoaded(now: Long) {
        if (!loaded) { load(now); loaded = true }
        // A backward wall-clock jump invalidates timing evidence; keep ordinary heartbeat recovery.
        if (now < state.lastAuth || (lastWall > 0 && now < lastWall - 120)) {
            state = State(estimate = state.estimate)
            eligible = false
            baselineKey = null
            persist(now)
            logger.warn("REAUTH_CLOCK_ROLLBACK_STATE_RESET")
        }
        lastWall = now
    }

    private fun load(now: Long) {
        if (!file.exists()) return
        try {
            require(file.isFile && file.length() <= 32 * 1024) { "PLANNER_STATE_INVALID" }
            val values = StateJson.decode(StateFiles.read(file))
            fun number(name: String) = values[name] as? Long ?: error("PLANNER_STATE_INVALID")
            val schema = values["schemaVersion"] as? Long ?: 1L
            require(schema in 1L..2L) { "PLANNER_SCHEMA_INVALID" }
            val estimate = number("estimatedLifetimeSeconds")
            val auth = number("lastAuthSuccessAt")
            val next = number("nextPlannedReauthAt")
            val updated = number("updatedAt")
            val observed = number("lastObservedLifetimeSeconds")
            val samples = number("successfulAuthSamples")
            require(estimate in MIN_INTERVAL..MAX_INTERVAL && auth in 0..now && updated in 1..now &&
                observed in 0..MAX_INTERVAL && samples in 0..1_000_000 && auth <= updated) { "PLANNER_STATE_INVALID" }
            require(next >= 0 && next <= now + MAX_INTERVAL && (auth > 0 || next == 0L)) { "PLANNER_STATE_INVALID" }
            val validNext = if (auth > 0 && next < auth + MIN_REAUTH_GAP) computeNext(auth, estimate) else next
            // Old counters did not distinguish outages or restarts, so they are not trusted samples.
            state = State(estimate, auth, validNext, if (schema == 2L) observed else 0, if (schema == 2L) samples.toInt() else 0)
            if (schema == 1L || validNext != next) persist(now)
        } catch (_: Exception) {
            state = State()
            logger.warn("REAUTH_STATE_INVALID_USING_DEFAULTS")
        }
        eligible = false // No sample may span a process restart.
    }

    private fun persist(now: Long) {
        try {
            writer(file, StateJson.encode(linkedMapOf(
                "schemaVersion" to 2,
                "estimatedLifetimeSeconds" to state.estimate,
                "lastAuthSuccessAt" to state.lastAuth,
                "nextPlannedReauthAt" to state.next,
                "lastObservedLifetimeSeconds" to state.observed,
                "successfulAuthSamples" to state.samples,
                "updatedAt" to now,
            )))
        } catch (_: Exception) { logger.warn("REAUTH_STATE_WRITE_FAILED") }
    }

    internal fun computeNext(auth: Long, estimate: Long): Long {
        val boundedEstimate = estimate.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        val lead = minOf(90L * 60, boundedEstimate / 4)
        val target = auth + maxOf(MIN_REAUTH_GAP, boundedEstimate - lead)
        if (isInWindow(target)) return target
        var aligned = Instant.ofEpochSecond(target).atZone(zone).toLocalDate().atTime(startHour, 0).atZone(zone)
        if (aligned.toEpochSecond() > target) aligned = aligned.minusDays(1)
        val alignedSeconds = aligned.toEpochSecond()
        return if (alignedSeconds >= auth + MIN_REAUTH_GAP) alignedSeconds else target
    }

    internal fun isInWindow(epoch: Long): Boolean {
        val time = Instant.ofEpochSecond(epoch).atZone(zone)
        val minutes = time.hour * 60 + time.minute
        val start = startHour * 60; val end = endHour * 60
        return if (end > start) minutes in start until end
        else if (end < start) minutes >= start || minutes < end else true
    }
    private fun overdue(now: Long): Boolean {
        val hours = if (endHour == startHour) 24 else if (endHour > startHour) endHour - startHour else 24 - startHour + endHour
        return now - state.next >= hours * 3600L
    }
}
