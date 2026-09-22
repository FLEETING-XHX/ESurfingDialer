package com.rsplwe.esurfing

object RuntimeConfig {
    @Volatile
    private var enhancedConnection: Boolean = envBoolean("ENHANCED_CONNECTION_ENABLED", true)

    val stateDir: String = env("STATE_DIR", "target")
    val loginRetryInitialSeconds: Long = envLong("LOGIN_RETRY_INITIAL_SECONDS", 5, 1, 600)
    val loginRetryMaxSeconds: Long = envLong("LOGIN_RETRY_MAX_SECONDS", 60, 5, 1800)
    val heartbeatFailureThreshold: Int = envInt("HEARTBEAT_FAILURE_THRESHOLD", 3, 1, 20)
    val heartbeatIntervalMaxSeconds: Long = envLong("HEARTBEAT_INTERVAL_MAX_SECONDS", 240, 30, 600)
    val portalDetectionThreshold: Int = envInt("PORTAL_DETECTION_THRESHOLD", 12, 1, 120)
    val postLoginPortalGraceSeconds: Long = envLong("POST_LOGIN_PORTAL_GRACE_SECONDS", 20, 5, 300)
    val portalReauthCooldownSeconds: Long = envLong("PORTAL_REAUTH_COOLDOWN_SECONDS", 300, 30, 7200)
    val loginConfirmationAttempts: Int = envInt("LOGIN_CONFIRMATION_ATTEMPTS", 3, 1, 10)
    val loginConfirmationIntervalSeconds: Long = envLong("LOGIN_CONFIRMATION_INTERVAL_SECONDS", 2, 1, 30)
    private val enhancedNetworkCheckIntervalSeconds: Long = envLong("ENHANCED_NETWORK_CHECK_INTERVAL_SECONDS", 5, 2, 300)
    private val conservativeNetworkCheckIntervalSeconds: Long = envLong("CONSERVATIVE_NETWORK_CHECK_INTERVAL_SECONDS", 20, 5, 300)
    private val enhancedHealthWriteIntervalSeconds: Long = envLong("ENHANCED_HEALTH_WRITE_INTERVAL_SECONDS", 15, 5, 300)
    private val conservativeHealthWriteIntervalSeconds: Long = envLong("CONSERVATIVE_HEALTH_WRITE_INTERVAL_SECONDS", 30, 10, 300)
    private val enhancedSuspiciousCheckIntervalSeconds: Long = envLong("ENHANCED_SUSPICIOUS_CHECK_INTERVAL_SECONDS", 2, 1, 30)
    private val conservativeSuspiciousCheckIntervalSeconds: Long = envLong("CONSERVATIVE_SUSPICIOUS_CHECK_INTERVAL_SECONDS", 5, 2, 60)
    val networkProbeTimeoutSeconds: Long = envLong("NETWORK_PROBE_TIMEOUT_SECONDS", 3, 1, 15)
    val networkProbeBudgetSeconds: Long = envLong("NETWORK_PROBE_BUDGET_SECONDS", 8, 2, 30)
    const val networkProbeBodyLimitBytes: Long = 64 * 1024
    val maxAbnormalRecoveriesBeforeExit: Int = envInt("MAX_ABNORMAL_RECOVERIES_BEFORE_EXIT", 30, 3, 1000)

    val networkCheckIntervalSeconds: Long
        get() = if (enhancedConnection) enhancedNetworkCheckIntervalSeconds else conservativeNetworkCheckIntervalSeconds

    val healthWriteIntervalSeconds: Long
        get() = if (enhancedConnection) enhancedHealthWriteIntervalSeconds else conservativeHealthWriteIntervalSeconds

    fun networkCheckDelaySeconds(suspicious: Boolean): Long = when {
        !suspicious -> networkCheckIntervalSeconds
        enhancedConnection -> minOf(networkCheckIntervalSeconds, enhancedSuspiciousCheckIntervalSeconds)
        else -> minOf(networkCheckIntervalSeconds, conservativeSuspiciousCheckIntervalSeconds)
    }

    fun setEnhancedConnection(enabled: Boolean) {
        if (enhancedConnection == enabled) return
        enhancedConnection = enabled
        CoreSignals.wakeNetworkMonitor()
        HealthStatus.requestWrite()
    }

    fun isEnhancedConnectionEnabled(): Boolean = enhancedConnection

    val networkCheckUrls: List<String> = env("NETWORK_CHECK_URLS", "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty {
            listOf(
                "http://www.gstatic.com/generate_204",
                "http://connect.rom.miui.com/generate_204",
                "http://www.msftconnecttest.com/connecttest.txt",
            )
        }

    private fun env(name: String, defaultValue: String): String =
        System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: defaultValue

    private fun envBoolean(name: String, defaultValue: Boolean): Boolean =
        when (env(name, if (defaultValue) "1" else "0").lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> defaultValue
        }

    private fun envLong(name: String, defaultValue: Long, min: Long, max: Long): Long =
        env(name, defaultValue.toString()).toLongOrNull()?.coerceIn(min, max) ?: defaultValue

    private fun envInt(name: String, defaultValue: Int, min: Int, max: Int): Int =
        env(name, defaultValue.toString()).toIntOrNull()?.coerceIn(min, max) ?: defaultValue
}
