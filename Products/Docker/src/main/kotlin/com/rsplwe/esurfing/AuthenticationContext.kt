package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.NetworkConnectivityResult

data class PortalCandidate(val portalUrl: String? = null, val portalBody: String? = null,
                           val userIp: String = "", val acIp: String = "") {
    fun discover() = PortalConfiguration.discover(portalUrl, portalBody, userIp, acIp)

    // 只解析有界缓存正文，不探测网络；随机 HTML 不改变认证重试目标。
    private val retryEndpoints: AuthenticationEndpoints? by lazy {
        try { portalBody?.let(PortalConfiguration::parse) }
        catch (_: AuthenticationFailure) { null }
    }

    internal fun sameRetryTarget(other: PortalCandidate?): Boolean = other != null &&
        portalUrl == other.portalUrl && userIp == other.userIp && acIp == other.acIp &&
        retryEndpoints == other.retryEndpoints
}

/** Captured once; a probe may update the candidate but cannot change this session. */
data class AuthenticationContext(val endpoints: AuthenticationEndpoints, val clientId: String, val mac: String)

fun rememberPortal(result: NetworkConnectivityResult): PortalCandidate {
    val candidate = PortalCandidate(result.portalUrl, result.portalBody, result.userIp.orEmpty(), result.acIp.orEmpty())
    States.portalCandidate = candidate
    States.userIp = result.userIp.orEmpty()
    States.acIp = result.acIp.orEmpty()
    return candidate
}

interface AuthenticationSession {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
    fun getSessionId(): Long
    fun free()
}
