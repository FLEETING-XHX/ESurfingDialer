package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AuthorizationRequest(val version: Long, val portal: NetworkConnectivityResult, val scheduled: Boolean = false)

/** Ignore page nonces, but keep endpoint and address changes distinct. Never log this key. */
internal fun portalIdentity(portal: NetworkConnectivityResult?): String {
    val url = portal?.portalUrl?.toHttpUrlOrNull()
    return "${portal?.userIp}|${portal?.acIp}|${url?.scheme}|${url?.host}|${url?.port}|${url?.encodedPath}"
}


object States {

    val rootDir = File(RuntimeConfig.stateDir)

    var clientId = ""
    var algoId = ""
    var macAddress = ""
    @Volatile var userIp = ""
    @Volatile var acIp = ""
    @Volatile var portal: NetworkConnectivityResult? = null
    var ticket = ""

    @Volatile
    var networkStatus: ConnectivityStatus = ConnectivityStatus.DEFAULT

    fun updateNetworkStatus(status: ConnectivityStatus) {
        val changed = networkStatus != status
        networkStatus = status
        if (changed) CoreSignals.wakeClient()
    }

    @Volatile
    var isRunning = true

    private var requestedAuthorization = 0L
    private var pendingAuthorization: AuthorizationRequest? = null
    val authorizationRequestVersion: Long @Synchronized get() = requestedAuthorization
    val forceAuthorization: Boolean @Synchronized get() = isRunning && pendingAuthorization != null

    @Synchronized fun requestAuthorization(candidate: NetworkConnectivityResult, scheduled: Boolean = false): Boolean {
        if (!isRunning) return false
        if (candidate.portalUrl == null && candidate.portalBody == null
            && !(PortalConfiguration.isIpv4(candidate.userIp.orEmpty()) && PortalConfiguration.isIpv4(candidate.acIp.orEmpty()))) return false
        val pending = pendingAuthorization
        if (pending != null && portalIdentity(pending.portal) == portalIdentity(candidate)) {
            // Ordinary portal recovery takes precedence over a cancellable daily plan.
            pendingAuthorization = pending.copy(portal = candidate, scheduled = pending.scheduled && scheduled)
            return false
        }
        pendingAuthorization = AuthorizationRequest(++requestedAuthorization, candidate, scheduled)
        CoreSignals.wakeClient()
        return true
    }

    @Synchronized fun observePortal(candidate: NetworkConnectivityResult) {
        portal = candidate
        val pending = pendingAuthorization
        if (pending != null && portalIdentity(pending.portal) == portalIdentity(candidate))
            pendingAuthorization = pending.copy(portal = candidate)
        // Completion uses the same lock: an in-flight older login cannot erase a newly observed portal.
        if (!HealthStatus.authenticated) requestAuthorization(candidate)
    }

    @Synchronized fun requestSessionRecovery(candidate: NetworkConnectivityResult) {
        if (pendingAuthorization == null) requestAuthorization(candidate)
    }

    @Synchronized fun authorizationRequest(): AuthorizationRequest? = if (isRunning) pendingAuthorization else null

    @Synchronized fun acknowledgeAuthorization(version: Long) {
        if (pendingAuthorization?.version == version) pendingAuthorization = null
        if (forceAuthorization) CoreSignals.wakeClient()
    }

    @Synchronized fun markAuthorizationStarted(version: Long): Boolean {
        // Once started, any retry belongs to ordinary authentication recovery, not the scheduler switch.
        val pending = pendingAuthorization
        if (!isRunning || pending?.version != version) return false
        pendingAuthorization = pending.copy(scheduled = false)
        return true
    }

    @Synchronized fun completeAuthorization(version: Long, heartbeatSeconds: Long): Boolean {
        if (!isRunning) return false
        HealthStatus.markLoginSuccess(heartbeatSeconds)
        acknowledgeAuthorization(version)
        return true
    }

    @Synchronized fun cancelAuthorization() { pendingAuthorization = null; CoreSignals.wakeClient() }

    @Synchronized fun cancelScheduledAuthorization() {
        if (pendingAuthorization?.scheduled == true) { pendingAuthorization = null; CoreSignals.wakeClient() }
    }

    var useDynarmic = false

    val ticketUrl: String
        get() {
            return "${Constants.BASE_URL}/ticket.cgi?wlanuserip=${userIp}&wlanacip=${acIp}&portal_node=${Constants.PORTAL_NODE}"
        }

}
