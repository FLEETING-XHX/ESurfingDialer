package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import java.io.File

/** One accepted request, with the portal that caused it; never log its candidate. */
data class AuthorizationRequest(val version: Long, val candidate: PortalCandidate, val trigger: AuthorizationTrigger)

object States {

    val rootDir = File(RuntimeConfig.stateDir)

    var clientId = ""
    var algoId = ""
    var macAddress = ""
    @Volatile var userIp = ""
    @Volatile var acIp = ""
    var ticket = ""
    @Volatile var portalCandidate = PortalCandidate()
        set(value) = synchronized(this) {
            field = value
            // Refresh a pending request's cached page, without admitting a different portal.
            val pending = pendingAuthorization
            if (pending != null && samePortal(pending.candidate, value)) {
                pendingAuthorization = pending.copy(candidate = value)
            }
        }

    @Volatile var networkStatus: ConnectivityStatus = ConnectivityStatus.DEFAULT

    private var authorizationVersion = 0L
    private var pendingAuthorization: AuthorizationRequest? = null

    /** Repeated observations refresh the cached page without creating an endless reauth queue. */
    @Synchronized fun requestAuthorization(candidate: PortalCandidate, trigger: AuthorizationTrigger = AuthorizationTrigger.PORTAL): Boolean {
        if (!isRunning) return false
        val pending = pendingAuthorization
        if (pending != null && samePortal(pending.candidate, candidate)) {
            pendingAuthorization = pending.copy(candidate = candidate)
            return false
        }
        pendingAuthorization = AuthorizationRequest(++authorizationVersion, candidate, trigger)
        return true
    }

    @Synchronized fun hasPendingAuthorization(): Boolean = isRunning && pendingAuthorization != null

    @Synchronized fun retryCandidate(): PortalCandidate = pendingAuthorization?.candidate ?: portalCandidate

    private fun samePortal(left: PortalCandidate, right: PortalCandidate): Boolean =
        left.portalUrl == right.portalUrl && left.userIp == right.userIp && left.acIp == right.acIp

    @Synchronized fun requestInitialAuthorization(candidate: PortalCandidate) {
        // Recheck under the same lock as completion: the monitor may have read false before login finished.
        if (!HealthStatus.authenticated) requestAuthorization(candidate)
    }

    /** Capture before terminating the old session or making any blocking call. */
    @Synchronized fun beginAuthorization(trigger: AuthorizationTrigger): AuthorizationRequest? {
        if (!isRunning) return null
        if (pendingAuthorization == null) requestAuthorization(portalCandidate, trigger)
        return pendingAuthorization
    }

    @Synchronized fun completeAuthorization(version: Long): Boolean {
        if (!isRunning) return false
        HealthStatus.markLoginSuccess()
        // A successful older attempt cannot acknowledge a newer portal request.
        if (pendingAuthorization?.version == version) pendingAuthorization = null
        return true
    }

    @Synchronized fun cancelAuthorization() {
        pendingAuthorization = null
    }

    @Volatile
    var isRunning = true

    var useDynarmic = false

    val ticketUrl: String
        get() {
            return "${Constants.BASE_URL}/ticket.cgi?wlanuserip=${userIp}&wlanacip=${acIp}&portal_node=${Constants.PORTAL_NODE}"
        }

}
