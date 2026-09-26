package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import java.io.File
import java.util.concurrent.atomic.AtomicLong


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

    private val requestedAuthorization = AtomicLong(0)
    private val completedAuthorization = AtomicLong(0)
    val authorizationRequestVersion: Long get() = requestedAuthorization.get()
    val forceAuthorization: Boolean get() = requestedAuthorization.get() != completedAuthorization.get()

    fun requestAuthorization() {
        requestedAuthorization.incrementAndGet()
        CoreSignals.wakeClient()
    }

    fun acknowledgeAuthorization(version: Long) {
        completedAuthorization.updateAndGet { maxOf(it, version) }
        if (forceAuthorization) CoreSignals.wakeClient()
    }

    var useDynarmic = false

    val ticketUrl: String
        get() {
            return "${Constants.BASE_URL}/ticket.cgi?wlanuserip=${userIp}&wlanacip=${acIp}&portal_node=${Constants.PORTAL_NODE}"
        }

}
