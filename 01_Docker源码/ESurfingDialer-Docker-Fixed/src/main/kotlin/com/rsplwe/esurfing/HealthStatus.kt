package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object HealthStatus {
    private val logger: Logger = Logger.getLogger(HealthStatus::class.java)

    @Volatile var clientThreadAlive: Boolean = false
    @Volatile var networkCheckThreadAlive: Boolean = false
    @Volatile var authenticated: Boolean = false
    @Volatile var lastNetworkCheckAt: Long = 0
    @Volatile var lastLoginSuccessAt: Long = 0
    @Volatile var lastHeartbeatSuccessAt: Long = 0
    @Volatile var lastPortalReauthRequestAt: Long = 0
    @Volatile var lastError: String? = null
    val consecutiveHeartbeatFailures = AtomicInteger(0)
    val consecutivePortalDetections = AtomicInteger(0)

    fun startReporter() {
        thread(start = true, name = "health-reporter", isDaemon = false) {
            while (States.isRunning) {
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
            val escapedError = lastError?.take(240)?.replace("\\", "\\\\")?.replace("\"", "\\\"")
            File(States.rootDir, "health.json").writeText(
                """
                {
                  "processAlive": true,
                  "clientThreadAlive": $clientThreadAlive,
                  "networkCheckThreadAlive": $networkCheckThreadAlive,
                  "authenticated": $authenticated,
                  "lastUpdatedAt": $now,
                  "lastNetworkCheckAt": $lastNetworkCheckAt,
                  "lastLoginSuccessAt": $lastLoginSuccessAt,
                  "lastHeartbeatSuccessAt": $lastHeartbeatSuccessAt,
                  "lastPortalReauthRequestAt": $lastPortalReauthRequestAt,
                  "autoReauthEnabled": ${planner.enabled},
                  "autoReauthEstimatedLifetimeSeconds": ${planner.estimatedLifetimeSeconds},
                  "autoReauthLastAuthSuccessAt": ${planner.lastAuthSuccessAt},
                  "autoReauthNextPlannedAt": ${planner.nextPlannedReauthAt},
                  "autoReauthLastObservedLifetimeSeconds": ${planner.lastObservedLifetimeSeconds},
                  "autoReauthSuccessfulAuthSamples": ${planner.successfulAuthSamples},
                  "autoReauthSafeWindowStartHour": ${planner.safeWindowStartHour},
                  "autoReauthSafeWindowEndHour": ${planner.safeWindowEndHour},
                  "consecutiveHeartbeatFailures": ${consecutiveHeartbeatFailures.get()},
                  "consecutivePortalDetections": ${consecutivePortalDetections.get()},
                  "lastError": ${if (escapedError == null) "null" else "\"$escapedError\""}
                }
                """.trimIndent() + "\n"
            )
        } catch (e: Exception) {
            logger.warn("Failed to write health status: ${e.message}")
        }
    }

    fun markNetworkCheckObserved() {
        networkCheckThreadAlive = true
        lastNetworkCheckAt = now()
    }

    fun markNetworkCheckSuccess() {
        markNetworkCheckObserved()
        consecutivePortalDetections.set(0)
    }

    fun markLoginSuccess() {
        authenticated = true
        lastLoginSuccessAt = now()
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
        lastError = null
    }

    fun markHeartbeatSuccess() {
        authenticated = true
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
        lastError = null
    }

    fun markPortalReauthRequested() {
        lastPortalReauthRequestAt = now()
    }

    fun secondsSinceLastPortalReauthRequest(): Long {
        if (lastPortalReauthRequestAt <= 0) return Long.MAX_VALUE
        return (now() - lastPortalReauthRequestAt).coerceAtLeast(0)
    }

    fun secondsSinceLastAuthSuccess(): Long {
        val lastAuthAt = maxOf(lastLoginSuccessAt, lastHeartbeatSuccessAt)
        if (lastAuthAt <= 0) return Long.MAX_VALUE
        return (now() - lastAuthAt).coerceAtLeast(0)
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

    private fun now(): Long = System.currentTimeMillis() / 1000
}
