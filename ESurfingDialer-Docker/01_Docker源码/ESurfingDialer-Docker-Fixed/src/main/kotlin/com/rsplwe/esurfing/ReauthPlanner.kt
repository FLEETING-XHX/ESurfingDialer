package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AuthorizationTrigger { INITIAL, PORTAL, SCHEDULED }

data class ReauthPlanSnapshot(
    val enabled: Boolean,
    val estimatedLifetimeSeconds: Long,
    val lastAuthSuccessAt: Long,
    val nextPlannedReauthAt: Long,
    val lastObservedLifetimeSeconds: Long,
    val successfulAuthSamples: Int,
    val safeWindowStartHour: Int,
    val safeWindowEndHour: Int,
    val mode: String = "daily",
    val handledDay: Long = -1,
    val stateInvalid: Boolean = false,
)

object ReauthPlanner {
    private val engine by lazy { ReauthPlannerEngine(File(States.rootDir, "reauth-plan.json"),
        RuntimeConfig.autoReauthEnabled, RuntimeConfig.autoReauthSafeWindowStartHour,
        RuntimeConfig.autoReauthSafeWindowEndHour) }
    private val zone = ZoneId.of("+8") // Campus scheduling uses UTC+8, independent of the log TZ.
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)

    fun networkKey(userIp: String, acIp: String): String? =
        if (PortalConfiguration.isIpv4(userIp) && PortalConfiguration.isIpv4(acIp)) "$userIp|$acIp" else null

    fun observeNetwork(result: NetworkConnectivityResult) {
        val key = networkKey(result.userIp.orEmpty(), result.acIp.orEmpty())
        when (result.status) {
            ConnectivityStatus.SUCCESS -> Unit
            ConnectivityStatus.IS_REDIRECTS_FOUND_IP -> engine.observePortal(key)
            else -> engine.invalidateSample()
        }
    }

    fun invalidateSample() = engine.invalidateSample()
    fun recordHeartbeatFailure(protocolRejected: Boolean) = engine.recordHeartbeatFailure(protocolRejected)
    fun recordAuthSuccess(trigger: AuthorizationTrigger, nowSeconds: Long = nowSeconds(), networkKey: String? = null) =
        engine.recordAuthSuccess(trigger, nowSeconds, networkKey)
    fun shouldTriggerScheduledReauth(nowSeconds: Long = nowSeconds()) = engine.shouldTrigger(nowSeconds)
    fun isDue(nowSeconds: Long = nowSeconds()) = engine.isDue(nowSeconds)
    fun isWaitingForSafeWindow(nowSeconds: Long = nowSeconds()) = engine.isWaiting(nowSeconds)
    fun shouldLogWaiting(nowSeconds: Long = nowSeconds()) = isWaitingForSafeWindow(nowSeconds)
    fun snapshot() = engine.snapshot()
    fun safeWindowLabel() = "%02d:00-%02d:00".format(RuntimeConfig.autoReauthSafeWindowStartHour, RuntimeConfig.autoReauthSafeWindowEndHour)
    fun formatEpoch(epochSeconds: Long): String = if (epochSeconds <= 0) "-" else runCatching { formatter.format(Instant.ofEpochSecond(epochSeconds)) }.getOrDefault("invalid")
    private fun nowSeconds() = System.currentTimeMillis() / 1000L
}
