package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object HealthStatus {
    private val logger: Logger = Logger.getLogger(HealthStatus::class.java)

    @Volatile var clientThreadAlive: Boolean = false
    @Volatile var networkCheckThreadAlive: Boolean = false
    @Volatile var authenticated: Boolean = false
    @Volatile var networkReachable: Boolean = false
    @Volatile var lastNetworkCheckAt: Long = 0
    @Volatile var lastNetworkCheckCompletedAt: Long = 0
    @Volatile var lastNetworkCheckSuccessAt: Long = 0
    private val processId = UUID.randomUUID().toString()
    private data class Progress(val stage: String, val startedAt: Long, val timeoutSeconds: Long, val startedNanos: Long = System.nanoTime())
    @Volatile private var progress = Progress("starting", now(), 120)

    fun markClientProgress(stage: String, timeoutSeconds: Long = 30) {
        require(stage.matches(Regex("[a-z_]+")))
        progress = Progress(stage, now(), timeoutSeconds.coerceIn(5, 600))
    }

    internal fun clientStageExpired(nanos: Long = System.nanoTime()): Boolean {
        val current = progress
        return nanos - current.startedNanos > current.timeoutSeconds * 1_000_000_000L
    }

    fun markNetworkCheckCompleted() { lastNetworkCheckCompletedAt = now() }
    @Volatile var lastLoginSuccessAt: Long = 0
    @Volatile var lastHeartbeatSuccessAt: Long = 0
    @Volatile var lastPortalReauthRequestAt: Long = 0
    @Volatile var lastError: String? = null
    val consecutiveHeartbeatFailures = AtomicInteger(0)
    val consecutivePortalDetections = AtomicInteger(0)

    fun startReporter() {
        thread(start = true, name = "health-reporter", isDaemon = false) {
            while (States.isRunning) {
                if (clientThreadAlive && clientStageExpired()) {
                    lastError = "CLIENT_STAGE_TIMEOUT stage=${progress.stage}"
                    clientThreadAlive = false
                    write()
                    logger.error(lastError)
                    // JNI may hold the Client monitor forever; a shutdown hook would deadlock.
                    // The container's restart policy handles this abnormal process exit.
                    Runtime.getRuntime().halt(1)
                }
                write()
                Thread.sleep(RuntimeConfig.healthWriteIntervalSeconds * 1000)
            }
            write()
        }
    }

    fun write() {
        try {
            val now = now()
            val planner = ReauthPlanner.snapshot()
            val currentProgress = progress
            fun past(value: Long) = value.takeIf { it in 0..now } ?: 0L
            StateFiles.write(File(States.rootDir, "health.json"), StateJson.encode(linkedMapOf(
                "schemaVersion" to 2,
                "networkCheckIntervalSeconds" to RuntimeConfig.networkCheckIntervalSeconds,
                "networkProbeBudgetSeconds" to RuntimeConfig.networkProbeBudgetSeconds,
                "healthWriteIntervalSeconds" to RuntimeConfig.healthWriteIntervalSeconds,
                "processId" to processId,
                "processAlive" to States.isRunning,
                "clientThreadAlive" to clientThreadAlive,
                "networkCheckThreadAlive" to networkCheckThreadAlive,
                "authenticated" to authenticated,
                "networkReachable" to networkReachable,
                "connectionState" to when {
                    authenticated -> "authenticated"
                    networkReachable && currentProgress.stage in setOf("loop", "network_online_unowned") -> "network-online-unowned"
                    else -> "authenticating"
                },
                "lastUpdatedAt" to now,
                "lastNetworkCheckAt" to past(lastNetworkCheckAt),
                "lastNetworkCheckCompletedAt" to past(lastNetworkCheckCompletedAt),
                "lastNetworkCheckSuccessAt" to past(lastNetworkCheckSuccessAt),
                "clientStage" to currentProgress.stage,
                "lastClientProgressAt" to currentProgress.startedAt,
                "clientStageTimeoutSeconds" to currentProgress.timeoutSeconds,
                "clientStageDeadlineAt" to currentProgress.startedAt + currentProgress.timeoutSeconds,
                "lastLoginSuccessAt" to past(lastLoginSuccessAt),
                "lastHeartbeatSuccessAt" to past(lastHeartbeatSuccessAt),
                "lastPortalReauthRequestAt" to past(lastPortalReauthRequestAt),
                "autoReauthEnabled" to planner.enabled,
                "autoReauthMode" to planner.mode,
                "autoReauthHandledDay" to planner.handledDay,
                "autoReauthStateInvalid" to planner.stateInvalid,
                "autoReauthEstimatedLifetimeSeconds" to planner.estimatedLifetimeSeconds,
                "autoReauthLastAuthSuccessAt" to planner.lastAuthSuccessAt,
                "autoReauthNextPlannedAt" to planner.nextPlannedReauthAt,
                "autoReauthLastObservedLifetimeSeconds" to planner.lastObservedLifetimeSeconds,
                "autoReauthSuccessfulAuthSamples" to planner.successfulAuthSamples,
                "autoReauthSafeWindowStartHour" to planner.safeWindowStartHour,
                "autoReauthSafeWindowEndHour" to planner.safeWindowEndHour,
                "consecutiveHeartbeatFailures" to consecutiveHeartbeatFailures.get(),
                "consecutivePortalDetections" to consecutivePortalDetections.get(),
                "lastError" to lastError?.take(240),
            )))
        } catch (e: Exception) {
            logger.warn("Failed to write health status: ${e.message}")
        }
    }

    fun markNetworkCheckObserved() {
        networkCheckThreadAlive = true
        lastNetworkCheckAt = now()
    }

    fun markNetworkCheckSuccess() {
        networkReachable = true
        markNetworkCheckObserved()
        lastNetworkCheckSuccessAt = now()
        consecutivePortalDetections.set(0)
    }

    fun markLoginSuccess() {
        authenticated = true
        lastLoginSuccessAt = now()
        val current = now()
        if (lastLoginSuccessAt > current) lastLoginSuccessAt = 0
        lastHeartbeatSuccessAt = current
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
        lastError = null
    }

    fun markHeartbeatSuccess() {
        authenticated = true
        val current = now()
        if (lastLoginSuccessAt > current) lastLoginSuccessAt = 0
        lastHeartbeatSuccessAt = current
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
        lastError = null
    }

    fun markPortalReauthRequested() {
        lastPortalReauthRequestAt = now()
    }

    fun secondsSinceLastPortalReauthRequest(): Long {
        if (lastPortalReauthRequestAt <= 0) return Long.MAX_VALUE
        return ageSeconds(now(), lastPortalReauthRequestAt)
    }

    fun secondsSinceLastAuthSuccess(): Long {
        val lastAuthAt = maxOf(lastLoginSuccessAt, lastHeartbeatSuccessAt)
        if (lastAuthAt <= 0) return Long.MAX_VALUE
        return ageSeconds(now(), lastAuthAt)
    }

    fun isAuthFresh(maxAgeSeconds: Long): Boolean =
        authenticated && secondsSinceLastAuthSuccess() <= maxAgeSeconds

    fun markError(message: String?) {
        lastError = message?.takeIf { it.isNotBlank() }
    }

    fun resetSession() {
        authenticated = false
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
    }

    internal fun ageSeconds(current: Long, timestamp: Long): Long =
        if (timestamp <= 0 || timestamp > current) Long.MAX_VALUE else current - timestamp

    private fun now(): Long = System.currentTimeMillis() / 1000
}
