package com.rsplwe.esurfing

object RuntimeConfig {
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
    val networkCheckIntervalSeconds: Long = envLong("NETWORK_CHECK_INTERVAL_SECONDS", 5, 1, 300)
    val healthWriteIntervalSeconds: Long = envLong("HEALTH_WRITE_INTERVAL_SECONDS", 5, 1, 300)
    val maxAbnormalRecoveriesBeforeExit: Int = envInt("MAX_ABNORMAL_RECOVERIES_BEFORE_EXIT", 30, 3, 1000)

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

    private fun envLong(name: String, defaultValue: Long, min: Long, max: Long): Long =
        env(name, defaultValue.toString()).toLongOrNull()?.coerceIn(min, max) ?: defaultValue

    private fun envInt(name: String, defaultValue: Int, min: Int, max: Int): Int =
        env(name, defaultValue.toString()).toIntOrNull()?.coerceIn(min, max) ?: defaultValue
}
