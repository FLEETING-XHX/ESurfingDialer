package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object HealthStatus {
    private val logger: Logger = Logger.getLogger(HealthStatus::class.java)
    private val writeSignal = Semaphore(0)
    private data class AuthenticationProgress(val stage: String, val startedAt: Long)
    @Volatile private var authenticationProgress: AuthenticationProgress? = null

    fun beginAuthenticationStage(stage: String) {
        require(stage in setOf("portal", "session", "ticket", "login", "confirm"))
        authenticationProgress = AuthenticationProgress(stage, now())
        requestWrite()
    }

    private fun endAuthenticationStage() {
        authenticationProgress = null
        requestWrite()
    }

    @Volatile var clientThreadAlive: Boolean = false
    @Volatile var networkCheckThreadAlive: Boolean = false
    @Volatile var authenticated: Boolean = false
    @Volatile var lastNetworkCheckAt: Long = 0
    @Volatile var lastLoginSuccessAt: Long = 0
    @Volatile var lastHeartbeatSuccessAt: Long = 0
    @Volatile var lastError: String? = null
    @Volatile var authenticationFailure: String? = null
    @Volatile var authenticationFailureDeterministic: Boolean = false
    @Volatile var authenticationBlocked: Boolean = false
    val consecutiveHeartbeatFailures = AtomicInteger(0)
    val consecutivePortalDetections = AtomicInteger(0)
    @Volatile var lastPortalReauthAt: Long = 0

    fun startReporter() {
        thread(start = true, name = "health-reporter", isDaemon = false) {
            while (States.isRunning) {
                write()
                try {
                    writeSignal.tryAcquire(RuntimeConfig.healthWriteIntervalSeconds, TimeUnit.SECONDS)
                    writeSignal.drainPermits()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            write()
        }
    }

    fun requestWrite() {
        if (writeSignal.availablePermits() == 0) writeSignal.release()
    }

    fun updateClientThreadAlive(value: Boolean) {
        if (clientThreadAlive == value) return
        clientThreadAlive = value
        requestWrite()
    }

    fun updateNetworkCheckThreadAlive(value: Boolean) {
        if (networkCheckThreadAlive == value) return
        networkCheckThreadAlive = value
        requestWrite()
    }

    @Synchronized fun write() {
        try {
            val now = now()
            val progress = authenticationProgress
            val escapedError = lastError?.take(240)?.flatMap { c ->
                when {
                    c == '\\' -> "\\\\".toList()
                    c == '"' -> "\\\"".toList()
                    c.code < 32 -> ("\\u" + c.code.toString(16).padStart(4, '0')).toList()
                    else -> listOf(c)
                }
            }?.joinToString("")
            val temporary = File(States.rootDir, "health.json.tmp")
            temporary.writeText(
                """
                {
                  "processAlive": ${States.isRunning},
                  "clientThreadAlive": $clientThreadAlive,
                  "networkCheckThreadAlive": $networkCheckThreadAlive,
                  "authenticated": $authenticated,
                  "authenticationFailure": ${authenticationFailure?.let { "\"$it\"" } ?: "null"},
                  "authenticationFailureDeterministic": $authenticationFailureDeterministic,
                  "authenticationBlocked": $authenticationBlocked,
                  "authenticationStage": ${progress?.let { "\"${it.stage}\"" } ?: "null"},
                  "authenticationStageStartedAt": ${progress?.startedAt ?: 0},
                  "enhancedConnection": ${RuntimeConfig.isEnhancedConnectionEnabled()},
                  "networkCheckIntervalSeconds": ${RuntimeConfig.networkCheckIntervalSeconds},
                  "healthWriteIntervalSeconds": ${RuntimeConfig.healthWriteIntervalSeconds},
                  "lastUpdatedAt": $now,
                  "lastNetworkCheckAt": $lastNetworkCheckAt,
                  "lastLoginSuccessAt": $lastLoginSuccessAt,
                  "lastHeartbeatSuccessAt": $lastHeartbeatSuccessAt,
                  "consecutiveHeartbeatFailures": ${consecutiveHeartbeatFailures.get()},
                  "lastError": ${if (escapedError == null) "null" else "\"$escapedError\""}
                }
                """.trimIndent() + "\n"
            )
            val target = File(States.rootDir, "health.json").toPath()
            try {
                Files.move(temporary.toPath(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            logger.warn("Failed to write health status: ${e.message}")
        }
    }

    fun markNetworkCheckSuccess() {
        networkCheckThreadAlive = true
        lastNetworkCheckAt = now()
        consecutivePortalDetections.set(0)
    }

    fun markLoginSuccess() {
        endAuthenticationStage()
        clearAuthenticationFailure()
        authenticated = true
        lastLoginSuccessAt = now()
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        lastError = null
        requestWrite()
    }

    fun markHeartbeatSuccess() {
        endAuthenticationStage()
        clearAuthenticationFailure()
        authenticated = true
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        lastError = null
        requestWrite()
    }

    fun markError(message: String?) {
        val normalized = message?.takeIf { it.isNotBlank() }
        if (lastError == normalized) return
        lastError = normalized
        requestWrite()
    }

    fun markAuthenticationFailure(failure: AuthenticationFailure, blocked: Boolean) {
        endAuthenticationStage()
        authenticationFailure = failure.code
        authenticationFailureDeterministic = failure.deterministic
        authenticationBlocked = blocked
        authenticated = false
        markError(failure.code)
        requestWrite()
    }

    fun clearAuthenticationFailure() {
        authenticationFailure = null
        authenticationFailureDeterministic = false
        authenticationBlocked = false
        requestWrite()
    }

    fun resetSession() {
        endAuthenticationStage()
        authenticated = false
        consecutiveHeartbeatFailures.set(0)
        consecutivePortalDetections.set(0)
        requestWrite()
    }

    private fun now(): Long = System.currentTimeMillis() / 1000
}
