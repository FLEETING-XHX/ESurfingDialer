package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.ConnectivityStatus
import java.io.File

object States {

    val rootDir = File(RuntimeConfig.stateDir)

    var clientId = ""
    var algoId = ""
    var macAddress = ""
    @Volatile var userIp = ""
    @Volatile var acIp = ""
    var ticket = ""

    @Volatile var networkStatus: ConnectivityStatus = ConnectivityStatus.DEFAULT

    @Volatile
    var isRunning = true

    var useDynarmic = false

    val ticketUrl: String
        get() {
            return "${Constants.BASE_URL}/ticket.cgi?wlanuserip=${userIp}&wlanacip=${acIp}&portal_node=${Constants.PORTAL_NODE}"
        }

}
