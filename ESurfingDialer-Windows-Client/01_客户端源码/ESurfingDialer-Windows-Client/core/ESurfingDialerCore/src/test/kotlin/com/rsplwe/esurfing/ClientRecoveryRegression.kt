package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the real client loop with transport/native substitutes, never an actual account. */
object ClientRecoveryRegression {
    fun run(): Int {
        var checks = 0
        fun verify(value: Boolean, label: String) { check(value) { label }; checks++ }
        fun candidate(host: String, ip: String) = NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            ip, "10.0.0.1", portalBody = "<!--//config.campus.js.chinatelecom.com" +
                "<auth-url>http://$host/auth.cgi</auth-url>" +
                "<ticket-url><![CDATA[http://$host/ticket.cgi?wlanuserip=$ip&wlanacip=10.0.0.1]]></ticket-url>" +
                "//config.campus.js.chinatelecom.com-->")
        val a = candidate("a.test", "10.0.0.2")
        val b = candidate("b.test", "10.0.0.3")
        val sent = mutableListOf<Pair<String, String>>()
        var loads = 0
        val identifier = "12345678-1234-5678-9abc-123456789abc"
        val zsm = byteArrayOf(1, 2, 3, 36) + identifier.toByteArray() + byteArrayOf(0)
        val client = Client(Options("synthetic", "synthetic"),
            { object : AuthenticationSession {
                override fun encrypt(value: String) = value
                override fun decrypt(value: String) = value
                override fun free() = Unit
            } },
            { _, _ ->
                if (++loads == 1) States.observePortal(b)
                zsm
            },
            { url, data ->
                sent += url to data
                NetResult.Success(when {
                    url.contains("/ticket.cgi") -> "<response><ticket>synthetic-ticket</ticket></response>"
                    url.contains("/auth.cgi") -> "<response><keep-url>http://keep.test/keep.cgi</keep-url><term-url>http://term.test/term.cgi</term-url><keep-retry>240</keep-retry></response>"
                    url.contains("/keep.cgi") -> "<response><interval>240</interval></response>"
                    else -> "terminated"
                }.toResponseBody())
            })
        val iteration = Client::class.java.getDeclaredMethod("runClientIteration").apply { isAccessible = true }
        States.isRunning = true
        States.cancelAuthorization()
        HealthStatus.resetSession()
        HealthStatus.clearAuthenticationFailure()
        States.requestAuthorization(a)
        States.updateNetworkStatus(ConnectivityStatus.REQUEST_ERROR)
        iteration.invoke(client)
        verify(HealthStatus.authenticated && loads == 1, "Valid pending request executes despite failed public probes")
        verify(sent.any { it.first.startsWith("http://a.test") } && sent.none { it.first.startsWith("http://b.test") },
            "In-flight attempt keeps the original portal endpoints")
        verify(States.forceAuthorization && States.authorizationRequest()?.portal == b,
            "Older login success retains portal observed during bootstrap")
        iteration.invoke(client)
        verify(!States.forceAuthorization && loads == 2 && sent.any { it.first.startsWith("http://b.test") },
            "Next iteration consumes the queued newer portal once")
        verify(sent.first { it.first.contains("/term.cgi") }.second.contains("<ipv4>10.0.0.2</ipv4>"),
            "Old session termination uses old session address")
        verify(HealthStatus.progressAgeMillis() >= 0, "Successful confirmation starts monotonic heartbeat lease")
        client.session?.free()
        client.session = null

        // Stopping inside the failure-handler retry wait must not escape to the process crash handler.
        HealthStatus.resetSession()
        HealthStatus.clearAuthenticationFailure()
        States.cancelAuthorization()
        States.requestAuthorization(NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP,
            portalBody = "<!--//config.campus.js.chinatelecom.com<ticket-url>x</ticket-url>//config.campus.js.chinatelecom.com-->"))
        val errors = AtomicInteger()
        val worker = Thread(Client(Options("synthetic", "synthetic"))).apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> errors.incrementAndGet() }
        }
        worker.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (HealthStatus.authenticationFailure == null && System.nanoTime() < deadline) Thread.sleep(10)
            verify(HealthStatus.authenticationFailure == "PORTAL_AUTH_URL_MISSING", "Malformed portal fails before native initialization")
        } finally {
            States.isRunning = false
            States.cancelAuthorization()
            worker.interrupt()
            worker.join(3000)
        }
        verify(!worker.isAlive && errors.get() == 0 && !HealthStatus.clientThreadAlive,
            "Stop during failure backoff exits cleanly and publishes dead-thread state")
        States.isRunning = true
        HealthStatus.clearAuthenticationFailure()
        return checks
    }
}
