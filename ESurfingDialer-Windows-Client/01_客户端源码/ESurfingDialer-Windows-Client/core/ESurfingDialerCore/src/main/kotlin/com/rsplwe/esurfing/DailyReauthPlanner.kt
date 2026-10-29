package com.rsplwe.esurfing

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.apache.log4j.Logger

data class DailyReauthSnapshot(val enabled: Boolean, val startHour: Int, val endHour: Int,
                               val nextAt: Long, val status: String)

/** A durable calendar-day claim, independent of uptime, lifetime estimates and process restarts. */
class DailyReauthEngine(private val file: File, enabled: Boolean, startHour: Int, endHour: Int,
                        private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
                        private val write: (File, String) -> Unit = ::writeDailyState) {
    private val logger = Logger.getLogger(DailyReauthEngine::class.java)
    private var enabled = enabled
    private var startHour = startHour
    private var endHour = endHour
    private var lastClaimedDay = -1L
    private var loaded = false
    private var pendingContext = false
    private var writeFailed = false
    private var schemaUnsupported = false

    init { validate(startHour, endHour) }
    @Synchronized fun configure(enabled: Boolean, startHour: Int, endHour: Int) {
        validate(startHour, endHour)
        this.enabled = enabled; this.startHour = startHour; this.endHour = endHour
        pendingContext = false
    }
    @Synchronized fun isDue(now: Long = clock()): Boolean {
        load(now)
        return enabled && !schemaUnsupported && day(now) > lastClaimedDay && inWindow(now)
    }
    @Synchronized fun waitingForContext() { pendingContext = true }
    @Synchronized fun claim(now: Long = clock()): Boolean {
        if (!isDue(now)) return false
        val today = day(now)
        if (!persist(today, now)) return false
        lastClaimedDay = today
        pendingContext = false
        return true
    }
    @Synchronized fun recordAuthSuccess(now: Long = clock()) {
        load(now)
        if (schemaUnsupported) return
        // A real login in the window already supplies today's fresh session, even if scheduling is off.
        if (inWindow(now) && day(now) > lastClaimedDay) {
            lastClaimedDay = day(now)
            persist(lastClaimedDay, now)
        }
        pendingContext = false
    }
    @Synchronized fun snapshot(now: Long = clock()): DailyReauthSnapshot {
        load(now)
        val today = day(now)
        val start = startOf(today)
        val nextDay = maxOf(today + 1, lastClaimedDay + 1)
        val next = when {
            !enabled || schemaUnsupported -> 0L
            today <= lastClaimedDay -> startOf(nextDay)
            now < start -> start
            inWindow(now) -> start
            else -> startOf(nextDay)
        }
        val status = when {
            !enabled -> "disabled"
            schemaUnsupported -> "state-schema-unsupported"
            writeFailed -> "state-write-failed"
            pendingContext && isDue(now) -> "waiting-for-portal"
            today <= lastClaimedDay -> "completed-or-claimed"
            isDue(now) -> "due"
            else -> "waiting"
        }
        return DailyReauthSnapshot(enabled, startHour, endHour, next, status)
    }
    internal fun inWindow(now: Long): Boolean {
        val offset = now - startOf(day(now))
        val duration = if (endHour == startHour) 60L else (endHour - startHour) * 3600L
        return offset >= 0 && offset < duration
    }
    private fun day(now: Long) = Instant.ofEpochSecond(now).atOffset(ZoneOffset.ofHours(8)).toLocalDate().toEpochDay()
    private fun startOf(day: Long) = LocalDate.ofEpochDay(day).atTime(startHour, 0).toEpochSecond(ZoneOffset.ofHours(8))
    private fun load(now: Long) {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            require(file.isFile && file.length() <= 4096)
            val text = file.readText()
            val schemas = Regex("\"schemaVersion\"\\s*:\\s*(-?\\d+)").findAll(text).toList()
            if (schemas.size == 1 && schemas.single().groupValues[1].toLongOrNull()?.let { it != 1L } == true) {
                schemaUnsupported = true
                logger.warn("DAILY_REAUTH_STATE_SCHEMA_UNSUPPORTED")
                return
            }
            val member = "\"(?:schemaVersion|lastClaimedDay|updatedAt)\"\\s*:\\s*-?\\d+"
            require(Regex("\\s*\\{\\s*$member\\s*(?:,\\s*$member\\s*){2}\\}\\s*").matches(text))
            val values = Regex("\"(schemaVersion|lastClaimedDay|updatedAt)\"\\s*:\\s*(-?\\d+)").findAll(text).toList()
            require(values.size == 3 && values.map { it.groupValues[1] }.toSet().size == 3)
            fun field(name: String) = values.single { it.groupValues[1] == name }.groupValues[2].toLong()
            require(field("schemaVersion") == 1L && field("lastClaimedDay") in -1..2_932_896L && field("updatedAt") > 0)
            lastClaimedDay = field("lastClaimedDay")
        } catch (_: Exception) {
            // Missing history must not cause a second forced logout today.
            lastClaimedDay = day(now)
            persist(lastClaimedDay, now)
            logger.warn("DAILY_REAUTH_STATE_INVALID_SKIPPING_TODAY")
        }
    }
    private fun persist(day: Long, now: Long): Boolean = try {
        write(file, "{\n  \"schemaVersion\": 1,\n  \"lastClaimedDay\": $day,\n  \"updatedAt\": $now\n}\n")
        writeFailed = false
        true
    } catch (_: Exception) {
        if (!writeFailed) logger.warn("DAILY_REAUTH_STATE_WRITE_FAILED")
        writeFailed = true
        false
    }
    companion object {
        fun validate(startHour: Int, endHour: Int) {
            require(startHour in 0..23 && endHour in 0..24 && endHour >= startHour) { "结束时间不能早于起始时间" }
        }
    }
}

private fun writeDailyState(file: File, text: String) {
    file.parentFile.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), ".daily-reauth-", ".tmp")
    try {
        java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try { Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

/** Shares the request/start monitor: no completed disable can be followed by an old plan enqueue. */
internal class DailyReauthCoordinator(private val engine: DailyReauthEngine) {
    fun configure(enabled: Boolean, start: Int, end: Int) = synchronized(States) {
        val before = engine.snapshot()
        engine.configure(enabled, start, end)
        if (!enabled || before.enabled != enabled || before.startHour != start || before.endHour != end)
            States.cancelScheduledAuthorization()
        CoreSignals.wakeClient()
        HealthStatus.requestWrite()
    }

    fun request(candidate: com.rsplwe.esurfing.utils.NetworkConnectivityResult): Boolean = synchronized(States) {
        if (!States.isRunning || States.authorizationRequest() != null || !engine.claim()) return false
        States.requestAuthorization(candidate, scheduled = true)
    }
}

object DailyReauthPlanner {
    private val engine by lazy {
        DailyReauthEngine(File(States.rootDir, "daily-reauth-plan.json"), RuntimeConfig.autoReauthEnabled,
            RuntimeConfig.autoReauthStartHour, RuntimeConfig.autoReauthEndHour)
    }
    private val coordinator by lazy { DailyReauthCoordinator(engine) }
    fun configure(enabled: Boolean, start: Int, end: Int) = coordinator.configure(enabled, start, end)
    fun isDue() = engine.isDue()
    fun waitingForContext() = engine.waitingForContext()
    fun request(candidate: com.rsplwe.esurfing.utils.NetworkConnectivityResult) = coordinator.request(candidate)
    fun recordAuthSuccess() = engine.recordAuthSuccess()
    fun snapshot() = engine.snapshot()
}
