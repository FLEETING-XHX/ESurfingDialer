package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.NetworkConnectivityResult

data class PortalCandidate(val portalUrl: String? = null, val portalBody: String? = null,
                           val userIp: String = "", val acIp: String = "") {
    fun discover() = PortalConfiguration.discover(portalUrl, portalBody, userIp, acIp)
}

/** Captured once; a probe may update the candidate but cannot change this session. */
data class AuthenticationContext(val endpoints: AuthenticationEndpoints, val clientId: String, val mac: String)

fun rememberPortal(result: NetworkConnectivityResult) {
    States.portalCandidate = PortalCandidate(result.portalUrl, result.portalBody, result.userIp.orEmpty(), result.acIp.orEmpty())
    States.userIp = result.userIp.orEmpty()
    States.acIp = result.acIp.orEmpty()
}

interface AuthenticationSession {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
    fun getSessionId(): Long
    fun free()
}
