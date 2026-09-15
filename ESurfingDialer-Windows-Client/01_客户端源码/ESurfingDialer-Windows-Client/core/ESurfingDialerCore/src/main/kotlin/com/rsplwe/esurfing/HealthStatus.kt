package com.rsplwe.esurfing

import org.apache.log4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
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
    @Volatile var lastError: String? = null
    val consecutiveHeartbeatFailures = AtomicInteger(0)
    val consecutivePortalDetections = AtomicInteger(0)
    @Volatile var lastPortalReauthAt: Long = 0

    fun startReporter() {
        thread(start = true, name = "health-reporter", isDaemon = false) {
            while (States.isRunning) {
                write()
                Thread.sleep(RuntimeConfig.healthWriteIntervalSeconds * 1000)
            }
            write()
        }
    }

    @Synchronized fun write() {
        try {
            val now = now()
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
        authenticated = true
        lastLoginSuccessAt = now()
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        lastError = null
    }

    fun markHeartbeatSuccess() {
        authenticated = true
        lastHeartbeatSuccessAt = now()
        consecutiveHeartbeatFailures.set(0)
        lastError = null
    }

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
