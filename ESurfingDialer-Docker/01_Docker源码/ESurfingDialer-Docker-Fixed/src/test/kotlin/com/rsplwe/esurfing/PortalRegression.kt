package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.createProbeHttpClient
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.checkConnectivity
import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Logger
import org.apache.log4j.spi.LoggingEvent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic portal formats only; no real portal parameters, credentials or native loading. */
object PortalRegression {
    private var checks = 0
    private const val auth = "http://auth.test/auth.cgi"
    private const val ticket = "http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=test"
    private val fields = "<auth-url><![CDATA[$auth]]></auth-url><ticket-url><![CDATA[$ticket]]></ticket-url>"
    private fun page(xml: String) = "<!--//config.campus.js.chinatelecom.com\n$xml\n//config.campus.js.chinatelecom.com-->"
    private val valid = page(fields)
    private val missing = page(fields.replace("auth-url", "other-url"))
    private fun verify(label: String, value: Boolean) { check(value) { label }; checks++ }
    private fun failure(code: String, action: () -> Unit): AuthenticationFailure {
        try { action(); error("Expected $code") }
        catch (e: AuthenticationFailure) { verify("Expected $code, got ${e.code}", e.code == code); return e }
    }
    private fun parses(label: String, body: String) {
        val result = PortalConfiguration.parse(body)
        verify(label, result?.authUrl == auth && result.ticketUrl == ticket && result.userIp == "10.1.2.3")
    }

    fun run(): Int {
        formats()
        discovery()
        reporting()
        clientRecovery()
        changedCandidateEndsLongWait()
        return checks
    }

    private fun formats() {
        parses("CDATA", valid)
        parses("space before tag closure", page(fields.replace("auth-url>", "auth-url >").replace("ticket-url>", "ticket-url\n>")))
        parses("attributes do not replace text", page(fields.replace("<auth-url>", "<auth-url label=\"ignored > text\">")))
        parses("wrapped XML declaration", page("<?xml version=\"1.0\" encoding=\"UTF-8\"?><config>$fields</config>"))
        parses("BOM before declared XML", page("\uFEFF<?xml version=\"1.0\"?>$fields"))
        parses("plain escaped query", page("<auth-url>$auth</auth-url><ticket-url>${ticket.replace("&", "&amp;")}</ticket-url>"))
        parses("legacy plain raw query separators", page("<auth-url>$auth</auth-url><ticket-url>$ticket</ticket-url>"))
        parses("mixed text and CDATA", page(fields.replace("<![CDATA[$auth]]>", "http://<![CDATA[auth.test]]>/auth.cgi")))
        parses("adjacent CDATA", page(fields.replace("auth.test", "auth.]]><![CDATA[test")))
        parses("numeric XML entities", page(fields.replace("<![CDATA[$auth]]>", "http://auth.test/auth.c&#103;&#x69;")))
        parses("commented example ignored", page("<!--<auth-url>example</auth-url>-->$fields"))
        val literal = PortalConfiguration.parse(page(fields.replace("portal_node=test", "portal_node=a&amp;b=1")))!!
        verify("CDATA ampersand bytes preserved", literal.ticketUrl.endsWith("portal_node=a&amp;b=1"))
        val absent = failure("PORTAL_AUTH_URL_MISSING") { PortalConfiguration.parse(missing) }
        verify("missing counts attached", absent.portalDiagnostics?.authFields == 0 && absent.portalDiagnostics?.ticketFields == 1)
        failure("PORTAL_TICKET_URL_MISSING") { PortalConfiguration.parse(page(fields.replace("ticket-url", "other-url"))) }
        failure("PORTAL_AUTH_URL_DUPLICATE") { PortalConfiguration.parse(page("<auth-url>$auth</auth-url>$fields")) }
        failure("PORTAL_TICKET_URL_DUPLICATE") { PortalConfiguration.parse(page("$fields<ticket-url><![CDATA[$ticket]]></ticket-url>")) }
        failure("PORTAL_AUTH_URL_EMPTY") { PortalConfiguration.parse(page(fields.replace("<![CDATA[$auth]]>", " "))) }
        failure("PORTAL_TICKET_URL_EMPTY") { PortalConfiguration.parse(page(fields.replace("<![CDATA[$ticket]]>", ""))) }
        failure("PORTAL_AUTH_URL_STRUCTURE_INVALID") { PortalConfiguration.parse(page(fields.replace("<![CDATA[$auth]]>", "<nested>$auth</nested>"))) }
        parses("unique fields inside configuration wrappers", page(fields.replace("<auth-url>", "<nested><auth-url>").replace("</auth-url>", "</auth-url></nested>")))
        failure("PORTAL_CONFIG_DUPLICATE") { PortalConfiguration.parse(valid + valid) }
        failure("PORTAL_CONFIG_TRUNCATED") { PortalConfiguration.parse(valid.substringBefore("//config.campus.js.chinatelecom.com-->")) }
        failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page(fields.replace("</auth-url>", ""))) }
        failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///never-read'>]>$fields")) }
        failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page(fields.replace("<![CDATA[$auth]]>", "&unknown;"))) }
        failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page("<x>".repeat(70) + fields + "</x>".repeat(70))) }
        failure("PORTAL_BODY_TOO_LARGE") { PortalConfiguration.parse("x".repeat(128 * 1024 + 1)) }
        failure("PORTAL_AUTH_URL_INVALID") { PortalConfiguration.parse(page(fields.replace(auth, "http://127.0.0.1/auth.cgi"))) }
        failure("PORTAL_TICKET_URL_INVALID") { PortalConfiguration.parse(page(fields.replace(ticket, "file:///ticket.cgi"))) }
        failure("PORTAL_ENDPOINT_PATH_INVALID") { PortalConfiguration.parse(page(fields.replace("auth.cgi", "other.cgi"))) }
        failure("PORTAL_IP_INVALID") { PortalConfiguration.parse(page(fields.replace("10.1.2.3", "999.1.2.3"))) }
        failure("PORTAL_IP_INVALID") { PortalConfiguration.parse(page(fields.replace("portal_node=test", "wlanuserip=10.1.2.4"))) }
        val stderr = java.io.ByteArrayOutputStream()
        val original = System.err
        val invalid: AuthenticationFailure
        try {
            System.setErr(java.io.PrintStream(stderr))
            invalid = failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page("<private-account>$fields")) }
        } finally { System.setErr(original) }
        verify("parser cause and stderr do not leak values", invalid.cause == null && stderr.size() == 0 && !invalid.toString().contains("private-account"))
    }

    private fun discovery() {
        val requests = AtomicInteger()
        val ua = Collections.synchronizedList(mutableListOf<String?>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            ua += exchange.requestHeaders.getFirst("User-Agent")
            val path = exchange.requestURI.path
            if (path == "/redirect") {
                exchange.responseHeaders.add("Location", "/missing?secret=never-log-this")
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "text/html; private=never-log-this")
                val body = when (path) { "/valid" -> valid; "/missing" -> missing; else -> "ordinary page" }.toByteArray()
                exchange.sendResponseHeaders(if (path == "/error") 503 else 200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        server.start()
        val client = createProbeHttpClient(2).newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        val base = "http://portal.test:${server.address.port}"
        try {
            val refreshed = PortalConfiguration.discover("$base/valid", missing, "", "", client)
            verify("bad cached page refreshes from live portal", refreshed.authUrl == auth && requests.get() == 1)
            PortalConfiguration.discover("$base/valid", valid, "", "", client)
            verify("valid inline config needs no extra request", requests.get() == 1)
            val e = failure("PORTAL_AUTH_URL_MISSING") {
                PortalConfiguration.discover("$base/redirect", null, "10.0.0.2", "10.0.0.1", client)
            }
            verify("live HTTP failure has safe metadata", e.portalDiagnostics?.let {
                it.httpStatus == 200 && it.mediaType == PortalMediaType.HTML && it.hop == 2 && it.authFields == 0 && it.ticketFields == 1 && it.bodyBytes > 0
            } == true)
            verify("metadata excludes URL and header parameters", !e.portalDiagnostics.toString().contains("never-log-this"))
            val cached = failure("PORTAL_AUTH_URL_MISSING") { PortalConfiguration.discover("$base/plain", missing, "10.0.0.2", "10.0.0.1", client) }
            verify("cached field counts distinguished from current HTTP response", cached.portalDiagnostics?.cachedConfig == true)
            failure("PORTAL_AUTH_URL_MISSING") { PortalConfiguration.discover(null, missing, "10.0.0.2", "10.0.0.1", client) }
            val http = failure("PORTAL_HTTP_ERROR") { PortalConfiguration.discover("$base/error", null, "", "", client) }
            verify("non-success HTTP status retained", http.portalDiagnostics?.httpStatus == 503)
            verify("missing config probe is not online", checkConnectivity(listOf("$base/missing"), client).status == ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP)
            verify("UA unchanged across discovery requests", ua.isNotEmpty() && ua.all { it == Constants.USER_AGENT })
        } finally { server.stop(0); client.connectionPool.evictAll() }
    }

    private fun reporting() {
        var nanos = 0L
        val reports = AuthenticationFailureReports { nanos }
        val candidate = PortalCandidate(portalUrl = "http://portal.test/?secret=never-log-this", portalBody = missing)
        val error = failure("PORTAL_AUTH_URL_MISSING") { PortalConfiguration.parse(missing) }
        val first = reports.report(error, candidate, 5)
        verify("first error concise and redacted", first?.contains("authFields=0") == true && !first.contains("never-log-this") && !first.contains("http://"))
        nanos += TimeUnit.SECONDS.toNanos(5)
        verify("same error suppressed", reports.report(error, candidate, 10) == null)
        val resized = AuthenticationFailure(error.code, true, error.portalDiagnostics?.copy(bodyChars = 999))
        verify("page timestamp and size changes do not flood logs", reports.report(resized, candidate.copy(portalBody = missing + "timestamp"), 10) == null)
        nanos = TimeUnit.SECONDS.toNanos(60)
        verify("periodic summary includes suppressed count", reports.report(error, candidate, 60)?.contains("suppressed=2") == true)
        verify("changed candidate reports immediately", reports.report(error, candidate.copy(userIp = "10.0.0.3"), 5) != null)
        reports.reset()
        verify("successful session resets suppression", reports.report(error, candidate, 5) != null)
    }

    private fun clientRecovery() {
        val errors = CountDownLatch(1)
        val loggedIn = CountDownLatch(1)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val throwables = AtomicInteger()
        val appender = object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) {
                val message = event.renderedMessage
                messages += message
                if (event.throwableInformation != null) throwables.incrementAndGet()
                if (message.startsWith("AUTHORIZATION_FAILED")) errors.countDown()
                if (message == "LOGIN_SUCCESS") loggedIn.countDown()
            }
            override fun close() = Unit
            override fun requiresLayout() = false
        }
        val logger = Logger.getLogger(Client::class.java)
        val sends = AtomicInteger()
        val loads = AtomicInteger()
        var bootstrap = true
        val fake = object : AuthenticationSession {
            override fun getSessionId() = 17L
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
            override fun free() = Unit
        }
        val client = Client(Options("fixture-user", "fixture-password"), { loads.incrementAndGet(); fake }, { url, _, _ ->
            sends.incrementAndGet()
            NetResult.Success(when {
                bootstrap -> { bootstrap = false; "bootstrap" }
                url.startsWith("http://ticket.test") -> "<response><ticket>fixture</ticket></response>"
                url.startsWith("http://auth.test") -> "<response><keep-url>http://keep.test/keep</keep-url><term-url>http://term.test/term</term-url><keep-retry>30</keep-retry></response>"
                url.startsWith("http://keep.test") -> "<response><interval>30</interval></response>"
                else -> "terminated"
            }.toResponseBody())
        })
        HealthStatus.resetSession()
        States.isRunning = true
        States.networkStatus = ConnectivityStatus.IS_REDIRECTS_FOUND_IP
        States.portalCandidate = PortalCandidate(portalBody = missing)
        val worker = Thread(client)
        logger.addAppender(appender)
        try {
            worker.start()
            verify("client reports portal failure", errors.await(3, TimeUnit.SECONDS))
            verify("failed portal cannot send credentials or load native code", sends.get() == 0 && loads.get() == 0 && !HealthStatus.authenticated)
            verify("health retains specific failure", HealthStatus.lastError?.startsWith("PORTAL_AUTH_URL_MISSING") == true)
            States.portalCandidate = PortalCandidate(portalBody = valid)
            // Allow the existing 5 s retry chunk plus the 2 s confirmation delay and scheduling overhead.
            verify("candidate change permits later login", loggedIn.await(10, TimeUnit.SECONDS) && HealthStatus.authenticated && loads.get() == 1)
            verify("expected failure logs no stack trace", throwables.get() == 0 && messages.none { it == "CLIENT_LOOP_RECOVERED" })
        } finally {
            States.isRunning = false; worker.interrupt(); worker.join(3000); client.shutdown()
            logger.removeAppender(appender)
        }
        verify("successful worker stop updates health", !worker.isAlive && !HealthStatus.clientThreadAlive)

        // Stop while sleeping inside the AuthenticationFailure catch, which the old loop did not clean up.
        HealthStatus.markError(null)
        States.isRunning = true
        States.networkStatus = ConnectivityStatus.IS_REDIRECTS_FOUND_IP
        States.portalCandidate = PortalCandidate(portalBody = missing)
        val stopped = Thread(Client(Options("fixture", "fixture")))
        val escaped = AtomicInteger()
        stopped.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> escaped.incrementAndGet() }
        try {
            stopped.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (HealthStatus.lastError == null && System.nanoTime() < deadline) Thread.sleep(10)
            verify("failure wait reached before stop", HealthStatus.lastError?.startsWith("PORTAL_AUTH_URL_MISSING") == true)
        } finally {
            States.isRunning = false; stopped.interrupt(); stopped.join(3000)
            States.networkStatus = ConnectivityStatus.DEFAULT
        }
        verify("failure wait stop publishes thread exit", !stopped.isAlive && !HealthStatus.clientThreadAlive && escaped.get() == 0)
        val persisted = StateJson.decode(StateFiles.read(java.io.File(States.rootDir, "health.json")))
        verify("interrupted worker persists final thread state", persisted["clientThreadAlive"] == false)
        verify("interrupted worker persists failure details", (persisted["lastError"] as? String)?.startsWith("PORTAL_AUTH_URL_MISSING") == true)
        States.isRunning = true
    }

    private fun changedCandidateEndsLongWait() {
        val original = PortalCandidate(portalBody = missing)
        States.portalCandidate = original
        States.isRunning = true
        val client = Client(Options("fixture", "fixture"))
        val wait = Client::class.java.getDeclaredMethod("waitForRetry", Long::class.javaPrimitiveType, PortalCandidate::class.java)
            .apply { isAccessible = true }
        val errors = AtomicInteger()
        val worker = Thread { try { wait.invoke(client, 60L, original) } catch (_: Exception) { errors.incrementAndGet() } }
        try {
            worker.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(10)
            verify("long failure backoff is sleeping", worker.state == Thread.State.TIMED_WAITING)
            States.portalCandidate = PortalCandidate(portalBody = valid)
            worker.join(6500)
            verify("new candidate interrupts 60 second retry within one chunk", !worker.isAlive && errors.get() == 0)
        } finally {
            States.isRunning = false; worker.interrupt(); worker.join(1000); States.isRunning = true
        }
    }
}
