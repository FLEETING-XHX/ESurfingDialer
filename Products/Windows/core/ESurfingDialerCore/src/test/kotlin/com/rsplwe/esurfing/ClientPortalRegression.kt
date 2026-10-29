package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.InvocationTargetException

/** Run the real client flow; bootstrap, native session and transport are synthetic substitutes. */
object ClientPortalRegression {
    fun run(): Int {
        var checks = 0
        fun verify(value: Boolean, label: String) { check(value) { label }; checks++ }
        val auth = "http://auth.test/auth.cgi"
        val ticketUrl = "http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1"
        val fields = "<auth-url><![CDATA[$auth]]></auth-url><ticket-url><![CDATA[$ticketUrl]]></ticket-url>"
        val repeated = "<settings><auth-url><![CDATA[$auth]]></auth-url></settings>"
        val compatible = "<config link=\"http://portal.test/entry?a=1&b=2\">$fields$repeated</config>"
        fun candidate(xml: String) = NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP,
            portalBody = "<!--//config.campus.js.chinatelecom.com$xml//config.campus.js.chinatelecom.com-->")
        val iteration = Client::class.java.getDeclaredMethod("runClientIteration").apply { isAccessible = true }
        val identifier = "12345678-1234-5678-9abc-123456789abc"
        val zsm = byteArrayOf(1, 2, 3, 36) + identifier.toByteArray() + byteArrayOf(0)

        fun exercise(xml: String, expectedFailure: String?) {
            val stages = mutableListOf<String>()
            var frees = 0
            val client = Client(Options("synthetic", "synthetic"),
                { bytes ->
                    verify(bytes.contentEquals(zsm), "Portal compatibility does not alter bootstrap bytes")
                    stages += "native"
                    object : AuthenticationSession {
                        override fun encrypt(value: String) = value
                        override fun decrypt(value: String) = value
                        override fun free() { frees++ }
                    }
                },
                { url, algorithm ->
                    verify(url == ticketUrl, "Client uses complete portal ticket URL after compatibility parsing")
                    verify(algorithm == "00000000-0000-0000-0000-000000000000", "Initial bootstrap algorithm contract is preserved")
                    stages += "bootstrap"
                    zsm
                },
                { url, _ ->
                    val reply = when (url.substringBefore('?')) {
                        "http://ticket.test/ticket.cgi" -> {
                            stages += "ticket"
                            "<response><ticket>synthetic-ticket</ticket></response>"
                        }
                        auth -> {
                            stages += "login"
                            "<response><keep-url>http://keep.test/keep.cgi</keep-url><term-url>http://term.test/term.cgi</term-url><keep-retry>240</keep-retry></response>"
                        }
                        "http://keep.test/keep.cgi" -> {
                            stages += "confirm"
                            "<response><interval>240</interval></response>"
                        }
                        // A deterministic fixture failure must not invoke the client's live fallback probe.
                        else -> throw AuthenticationFailure("SYNTHETIC_REQUEST_UNEXPECTED", true)
                    }
                    NetResult.Success(reply.toResponseBody())
                })
            States.isRunning = true
            States.cancelAuthorization()
            HealthStatus.resetSession()
            HealthStatus.clearAuthenticationFailure()
            States.requestAuthorization(candidate(xml))
            try {
                val failure = try {
                    iteration.invoke(client)
                    null
                } catch (e: InvocationTargetException) {
                    val cause = e.cause
                    if (cause !is AuthenticationFailure) throw e
                    cause
                }
                verify(failure?.code == expectedFailure, "Client portal result is ${expectedFailure ?: "confirmed"}")
                if (expectedFailure == null) {
                    verify(stages == listOf("bootstrap", "native", "ticket", "login", "confirm"),
                        "Combined portal reaches bootstrap, session, ticket, login and keep in order")
                    verify(HealthStatus.authenticated && !HealthStatus.authenticationBlocked,
                        "Successful keep confirms the real client flow and clears blocking state")
                    verify(!States.forceAuthorization && States.authorizationRequest() == null,
                        "Successful compatible portal completes its pending authorization")
                } else {
                    verify(stages.isEmpty(), "Rejected portal cannot reach bootstrap, native or credential submission")
                    verify(!HealthStatus.authenticated && client.session == null,
                        "Rejected portal does not create an authenticated session")
                }
            } finally {
                client.session?.free()
                client.session = null
                States.cancelAuthorization()
                HealthStatus.resetSession()
                HealthStatus.clearAuthenticationFailure()
            }
            verify(frees == if (expectedFailure == null) 1 else 0, "Synthetic session is released only when created")
        }

        exercise(compatible, null)
        exercise("$fields<auth-url>http://conflict.test/auth.cgi</auth-url>", "PORTAL_AUTH_URL_DUPLICATE")
        exercise("<config link=\"&unknown;\">$fields</config>", "PORTAL_CONFIG_XML_INVALID")
        return checks
    }
}
