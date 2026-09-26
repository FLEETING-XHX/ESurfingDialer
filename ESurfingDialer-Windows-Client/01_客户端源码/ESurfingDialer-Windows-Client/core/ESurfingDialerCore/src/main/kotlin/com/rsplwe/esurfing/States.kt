package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import java.io.File


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

    @Volatile
    var forceAuthorization = false

    var useDynarmic = false

    val ticketUrl: String
        get() {
            return "${Constants.BASE_URL}/ticket.cgi?wlanuserip=${userIp}&wlanacip=${acIp}&portal_node=${Constants.PORTAL_NODE}"
        }

}
