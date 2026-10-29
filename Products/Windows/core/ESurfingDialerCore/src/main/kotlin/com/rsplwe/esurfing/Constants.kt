package com.rsplwe.esurfing

object Constants {

    const val DEBUG = false

    const val PACKAGE_ID = "com.cndatacom.campus.cdccportalgd"
    // android64_vpn/2085 currently returns HTTP 200 with an empty bootstrap body.
    // Verified with the existing ARM64 native provider; keep portal and API requests on this channel.
    const val USER_AGENT = "CCTP/android11_64/2104"
    const val REQUEST_ACCEPT = "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"
    const val BASE_URL = "http://14.146.227.141:7001"
    const val PORTAL_NODE = "http://61.140.12.23:10002"
    const val CAPTIVE_URL = "http://www.gstatic.com/generate_204"
    const val AUTH_URL = "$BASE_URL/auth.cgi"
}
