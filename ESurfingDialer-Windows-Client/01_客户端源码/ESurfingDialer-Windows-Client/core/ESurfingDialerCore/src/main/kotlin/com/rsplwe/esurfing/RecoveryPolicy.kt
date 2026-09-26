package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult

object RecoveryPolicy {
    fun shouldReauthenticate(portalCount: Int, heartbeatFailures: Int, loginAge: Long, cooldownAge: Long): Boolean {
        if (loginAge <= RuntimeConfig.postLoginPortalGraceSeconds && heartbeatFailures == 0) return false
        if (cooldownAge < RuntimeConfig.portalReauthCooldownSeconds) return false
        return portalCount >= RuntimeConfig.portalDetectionThreshold || heartbeatFailures > 0
    }
}

/** Consecutive evidence belongs to one portal; a changed IP pair bypasses the old session's cooldown. */
class PortalRecoveryTracker {
    private var candidate: String? = null
    private var count = 0

    fun reset() { candidate = null; count = 0 }

    fun observe(result: NetworkConnectivityResult, userIp: String, acIp: String,
                heartbeatFailures: Int, loginAge: Long, cooldownAge: Long): Boolean {
        val portal = result.status == ConnectivityStatus.IS_REDIRECTS_FOUND_IP
            || (result.status == ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP
                && (result.portalUrl != null || result.portalBody != null))
        if (!portal) { reset(); return false }
        val validIps = PortalConfiguration.isIpv4(result.userIp.orEmpty())
            && PortalConfiguration.isIpv4(result.acIp.orEmpty())
        val identity = if (validIps) "${result.userIp}|${result.acIp}"
            else "missing|${AuthenticationDiagnostics.endpoint(result.portalUrl.orEmpty())}"
        count = if (candidate == identity) (count + 1).coerceAtMost(120) else 1
        candidate = identity
        val networkChanged = validIps && PortalConfiguration.isIpv4(userIp) && PortalConfiguration.isIpv4(acIp)
            && (result.userIp != userIp || result.acIp != acIp)
        if (networkChanged) return count >= 2
        return RecoveryPolicy.shouldReauthenticate(count, heartbeatFailures, loginAge, cooldownAge)
    }
}
