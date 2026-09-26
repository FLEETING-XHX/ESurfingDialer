package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.requestSessionBootstrap
import com.rsplwe.esurfing.network.readAuthenticationResponse
import com.rsplwe.esurfing.network.resetApiConnections
import com.rsplwe.esurfing.network.createProbeHttpClient
import com.rsplwe.esurfing.utils.checkConnectivity
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress

fun main() {
    var count = 0
    fun verify(condition: Boolean, name: String) { check(condition) { name }; count++; println("PASS: $name") }
    fun failure(code: String, block: () -> Unit) {
        val error = try { block(); null } catch (e: AuthenticationFailure) { e }
        verify(error?.code == code, code)
    }
    val id = "12345678-1234-5678-9abc-123456789abc"
    val zsm = byteArrayOf(1, 2, 3, 3) + "tag".toByteArray() + byteArrayOf(36) + id.toByteArray() + byteArrayOf(1, 2)
    verify(AuthenticationDiagnostics.inspect(zsm).algoId == id.uppercase(), "Bounded header UUID candidate")
    val swapped = byteArrayOf(1, 2, 3, 36) + id.toByteArray() + byteArrayOf(3) + "tag".toByteArray()
    verify(AuthenticationDiagnostics.inspect(swapped).algoId == id.uppercase(), "UUID in first header field")
    failure("ZSM_EMPTY") { AuthenticationDiagnostics.inspect(byteArrayOf()) }
    failure("ZSM_TRUNCATED") { AuthenticationDiagnostics.inspect(byteArrayOf(1, 2, 3, 4)) }
    failure("ZSM_TOO_LARGE") { AuthenticationDiagnostics.inspect(ByteArray(AuthenticationDiagnostics.MAX_ZSM_BYTES + 1)) }
    failure("ZSM_UNEXPECTED_TEXT") { AuthenticationDiagnostics.inspect("  <html>portal secret</html>".toByteArray()) }
    verify(AuthenticationDiagnostics.inspect(byteArrayOf(1, 2, 3, -1, 0)).algoId == null, "Truncated fields expose no UUID")
    verify(AuthenticationDiagnostics.inspect(byteArrayOf(1, 2, 3, 0, 0) + id.toByteArray()).algoId == null,
        "Does not scan payload for identifiers")
    verify(AuthenticationDiagnostics.endpoint("http://example.com:7001/ticket.cgi?ticket=secret") == "http://example.com:7001",
        "Endpoint excludes query and path")
    var algorithmCalls = 0
    var freed = 0L
    failure("NATIVE_SESSION_REJECTED") {
        NativeSessionLoader.load({ 0 }, { algorithmCalls++; id }, { freed = it })
    }
    verify(algorithmCalls == 0 && freed == 0L, "Rejected handle never passed to aid/free")
    failure("NATIVE_LOAD_EXCEPTION") { NativeSessionLoader.load({ error("secret") }, { id }, { }) }
    failure("NATIVE_ALGO_ID_INVALID") { NativeSessionLoader.load({ 7 }, { "secret" }, { freed = it }) }
    verify(freed == 7L, "Invalid algorithm frees valid native handle")
    failure("NATIVE_ALGO_ID_INVALID") { NativeSessionLoader.load({ 8 }, { error("secret") }, { freed = it }) }
    verify(freed == 8L, "Algorithm exception frees valid native handle")
    verify(NativeSessionLoader.load({ 9 }, { id }, { error("must not free") }).first == 9L,
        "Valid native load preserves handle")
    val retry = AuthenticationRetryPolicy()
    val rejected = AuthenticationFailure("NATIVE_SESSION_REJECTED", true)
    verify(!retry.record(rejected) && !retry.record(rejected) && retry.record(rejected), "Same deterministic failure pauses at three")
    verify(!retry.record(AuthenticationFailure("TICKET_TRANSPORT_ERROR")) && !retry.record(rejected), "Transient failure resets deterministic streak")
    retry.reset()
    verify(!retry.record(rejected), "Manual/new portal reset restores budget")

    fun config(ticket: String, auth: String = "http://example.com:7001/auth.cgi") =
        "<!--//config.campus.js.chinatelecom.com<auth-url><![CDATA[$auth]]></auth-url>" +
        "<ticket-url><![CDATA[$ticket]]></ticket-url>//config.campus.js.chinatelecom.com-->"
    val ticket = "http://example.com:7001/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.2.3.4&portal_node=http%3A%2F%2Fexample.com%3A10002"
    val portal = PortalConfiguration.parse(config(ticket))!!
    verify(portal.ticketUrl == ticket && portal.source == "portal" && portal.userIp == "10.1.2.3", "Dynamic portal preserves complete ticket URL")
    verify(PortalConfiguration.parse(config(ticket).replace("<![CDATA[", "").replace("]]>", "").replace("&", "&amp;"))!!.acIp == "10.2.3.4",
        "Escaped XML query parameters")
    verify(PortalConfiguration.parse("normal page") == null, "Missing config permits explicit legacy fallback")
    verify(PortalConfiguration.discover(null, null, "10.1.2.3", "10.2.3.4").source == "legacy_fallback", "Legacy fallback validated")
    failure("PORTAL_CONFIG_TRUNCATED") { PortalConfiguration.parse(config(ticket).substringBefore("//config.campus.js.chinatelecom.com-->")) }
    failure("PORTAL_INVALID_URL") { PortalConfiguration.parse(config(ticket, "file:///auth.cgi")) }
    failure("PORTAL_INVALID_URL") { PortalConfiguration.parse(config(ticket, "http://localhost/auth.cgi")) }
    failure("PORTAL_INVALID_URL") { PortalConfiguration.parse(config(ticket, "http://user:secret@example.com/auth.cgi")) }
    failure("PORTAL_IP_INVALID") { PortalConfiguration.parse(config(ticket.replace("10.1.2.3", "999.1.2.3"))) }
    failure("PORTAL_IP_INVALID") { PortalConfiguration.parse(config(ticket + "&wlanuserip=10.9.8.7")) }
    failure("PORTAL_IP_INVALID") { PortalConfiguration.parse(config(ticket.replace("wlanacip=10.2.3.4", "none=1"))) }
    failure("PORTAL_CONFIG_INVALID") { PortalConfiguration.parse(config(ticket.replace("ticket.cgi", "random.cgi"))) }

    verify(AuthenticationProtocol.ticket("<response><ticket><![CDATA[token&<>]]></ticket></response>") == "token&<>",
        "Ticket CDATA parsed without markup leakage")
    failure("TICKET_XML_INVALID") { AuthenticationProtocol.ticket("<response><ticket>secret</response>") }
    failure("TICKET_FIELDS_INVALID") { AuthenticationProtocol.ticket("<response><ticket>one</ticket><ticket>two</ticket></response>") }
    failure("TICKET_FIELDS_INVALID") { AuthenticationProtocol.ticket("<response><nested><ticket>one</ticket></nested></response>") }
    failure("TICKET_FIELDS_INVALID") { AuthenticationProtocol.ticket("<response><ticket><nested>one</nested></ticket></response>") }
    failure("TICKET_FIELDS_INVALID") { AuthenticationProtocol.ticket("<response><ticket> </ticket></response>") }
    failure("TICKET_XML_INVALID") {
        AuthenticationProtocol.ticket("<!DOCTYPE response [<!ENTITY leak SYSTEM 'file:///private'>]><response><ticket>&leak;</ticket></response>")
    }
    failure("TICKET_XML_TOO_LARGE") { AuthenticationProtocol.ticket("x".repeat(AuthenticationProtocol.MAX_XML_CHARS + 1)) }
    fun loginXml(keep: String, term: String = "http://example.com/term.cgi", retry: String = "30") =
        "<response><keep-url>$keep</keep-url><term-url>$term</term-url><keep-retry>$retry</keep-retry></response>"
    verify(AuthenticationProtocol.login(loginXml("<![CDATA[https://example.com/keep.cgi?a=1&b=2]]>")).retrySeconds == 30L,
        "Login accepts CDATA endpoints")
    verify(AuthenticationProtocol.login(loginXml("https://example.com/keep.cgi?a=1&amp;b=2")).keepUrl.endsWith("?a=1&b=2"),
        "Login accepts XML escaped endpoints")
    failure("LOGIN_URL_INVALID") { AuthenticationProtocol.login(loginXml("file:///private")) }
    failure("LOGIN_URL_INVALID") { AuthenticationProtocol.login(loginXml("http://localhost/keep.cgi")) }
    failure("LOGIN_URL_INVALID") { AuthenticationProtocol.login(loginXml("http://user:secret@example.com/keep.cgi")) }
    failure("LOGIN_FIELDS_INVALID") { AuthenticationProtocol.login("<response><keep-url>http://example.com/keep.cgi</keep-url></response>") }
    failure("LOGIN_INTERVAL_INVALID") { AuthenticationProtocol.login(loginXml("http://example.com/keep.cgi", retry = "unknown")) }
    verify(AuthenticationProtocol.heartbeat("<response><interval>30</interval></response>") == 30L, "Valid heartbeat interval confirms response")
    verify(AuthenticationProtocol.heartbeat("<response><interval>1</interval></response>") == 5L, "Valid positive interval has safe minimum")
    verify(AuthenticationProtocol.heartbeat("<response><interval>99999</interval></response>") == RuntimeConfig.heartbeatIntervalMaxSeconds,
        "Valid positive interval bounded by runtime maximum")
    failure("HEARTBEAT_FIELDS_INVALID") { AuthenticationProtocol.heartbeat("<response><error>login denied</error></response>") }
    failure("HEARTBEAT_FIELDS_INVALID") { AuthenticationProtocol.heartbeat("<html><body>portal</body></html>") }
    failure("HEARTBEAT_FIELDS_INVALID") { AuthenticationProtocol.heartbeat("<response><interval>30</interval><interval>60</interval></response>") }
    for (interval in listOf("0", "-5", "abc", "30s", "9223372036854775808")) {
        failure("HEARTBEAT_INTERVAL_INVALID") { AuthenticationProtocol.heartbeat("<response><interval>$interval</interval></response>") }
    }
    val secret = "p&<>'\"校园"
    val escaped = AuthenticationProtocol.escape(secret)
    verify(AuthenticationProtocol.ticket("<response><ticket>$escaped</ticket></response>") == secret,
        "Special characters survive request XML round trip")

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    fun body(path: String, data: ByteArray, chunked: Boolean = false) {
        server.createContext(path) { exchange ->
            try {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, if (chunked) 0 else data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            } finally { exchange.close() }
        }
    }
    body("/zsm", zsm)
    body("/empty", byteArrayOf())
    body("/oversized", ByteArray(AuthenticationDiagnostics.MAX_ZSM_BYTES + 1))
    body("/chunked", ByteArray(AuthenticationDiagnostics.MAX_ZSM_BYTES + 8192), true)
    body("/portal", config(ticket).toByteArray())
    body("/plain", "no campus config".toByteArray())
    body("/bad-portal", config(ticket, "file:///auth.cgi").toByteArray())
    body("/big-portal", ByteArray(128 * 1024 + 1))
    body("/bad-ip", "wlanuserip=999.1.2.3&wlanacip=10.2.3.4".toByteArray())
    server.createContext("/portal-redirect") {
        it.responseHeaders.add("Location", "/portal")
        it.sendResponseHeaders(302, -1); it.close()
    }
    server.createContext("/no-ip-redirect") {
        it.responseHeaders.add("Location", "/portal-redirect")
        it.sendResponseHeaders(302, -1); it.close()
    }
    server.createContext("/loop") {
        it.responseHeaders.add("Location", "/loop")
        it.sendResponseHeaders(302, -1); it.close()
    }
    server.createContext("/error") { it.sendResponseHeaders(503, -1); it.close() }
    server.createContext("/redirect") {
        it.responseHeaders.add("Location", "/zsm?wlanuserip=10.1.2.3&wlanacip=10.2.3.4")
        it.sendResponseHeaders(302, -1); it.close()
    }
    server.start()
    try {
        val base = "http://127.0.0.1:${server.address.port}"
        verify(requestSessionBootstrap("$base/zsm", id).contentEquals(zsm), "Bootstrap returns bounded body unchanged")
        failure("TICKET_HTTP_ERROR") { requestSessionBootstrap("$base/error", id) }
        failure("TICKET_HTTP_ERROR") { requestSessionBootstrap("$base/redirect", id) }
        failure("ZSM_EMPTY") { AuthenticationDiagnostics.inspect(requestSessionBootstrap("$base/empty", id)) }
        failure("ZSM_TOO_LARGE") { requestSessionBootstrap("$base/oversized", id) }
        failure("ZSM_TOO_LARGE") { requestSessionBootstrap("$base/chunked", id) }
        fun readReply(path: String) = com.rsplwe.esurfing.network.apiClient.newCall(okhttp3.Request.Builder().url("$base/$path").build())
            .execute().use { readAuthenticationResponse(it.body!!, "LOGIN") }
        verify(readReply("zsm").isNotEmpty(), "Bounded encrypted response readable")
        failure("LOGIN_BODY_EMPTY") { readReply("empty") }
        failure("LOGIN_BODY_TOO_LARGE") { readReply("oversized") }
        failure("LOGIN_BODY_TOO_LARGE") { readReply("chunked") }
        val noIp = checkConnectivity(listOf("$base/no-ip-redirect"))
        verify(noIp.portalUrl == "$base/portal-redirect"
            && noIp.status == com.rsplwe.esurfing.utils.ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP,
            "Redirect without IP preserves portal address")
        verify(checkConnectivity(listOf("$base/redirect")).portalUrl?.startsWith("$base/zsm?") == true,
            "Relative portal redirect resolved against probe URL")
        verify(checkConnectivity(listOf("$base/portal")).portalBody != null, "Inline portal configuration retained")
        verify(checkConnectivity(listOf("$base/bad-ip")).status == com.rsplwe.esurfing.utils.ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP,
            "Invalid portal IP is not accepted as ready authentication parameters")
        val localClient = createProbeHttpClient(2).newBuilder()
            .proxy(java.net.Proxy.NO_PROXY).dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1"))
            }).build()
        val portalBase = "http://example.com:${server.address.port}"
        fun discover(path: String) = PortalConfiguration.discover("$portalBase/$path", null, "10.1.2.3", "10.2.3.4", localClient)
        verify(discover("portal").ticketUrl == ticket, "Portal GET discovers dynamic endpoints")
        verify(discover("portal-redirect").ticketUrl == ticket, "Portal GET follows bounded relative redirect")
        verify(PortalConfiguration.discover("$portalBase/portal-redirect", null, "", "", localClient).userIp == "10.1.2.3",
            "IPs discovered from final portal config when initial redirect has none")
        failure("PORTAL_IP_INVALID") { PortalConfiguration.discover("$portalBase/plain", null, "", "", localClient) }
        verify(discover("plain").source == "legacy_fallback", "Only absent config falls back after GET")
        failure("PORTAL_INVALID_URL") { discover("bad-portal") }
        failure("PORTAL_BODY_TOO_LARGE") { discover("big-portal") }
        failure("PORTAL_HTTP_ERROR") { discover("error") }
        failure("PORTAL_REDIRECT_LIMIT") { discover("loop") }
        localClient.connectionPool.evictAll()
    } finally { server.stop(0); resetApiConnections() }
    States.rootDir.mkdirs()
    HealthStatus.beginAuthenticationStage("ticket")
    HealthStatus.write()
    verify(File(States.rootDir, "health.json").readText().contains("\"authenticationStage\": \"ticket\""), "Active authentication stage persisted")
    HealthStatus.resetSession()
    HealthStatus.write()
    verify(File(States.rootDir, "health.json").readText().contains("\"authenticationStage\": null"), "Reset clears stage lease")
    HealthStatus.markAuthenticationFailure(rejected, true)
    HealthStatus.write()
    verify(File(States.rootDir, "health.json").readText().contains("\"authenticationBlocked\": true"), "Blocked state persisted for supervisor")
    HealthStatus.resetSession()
    verify(HealthStatus.authenticationBlocked, "Session reset does not reset compatibility budget")
    HealthStatus.markLoginSuccess()
    verify(HealthStatus.authenticationFailure == null && !HealthStatus.authenticationBlocked, "Success clears protocol failure")
    println("PASS: $count authentication checks")
    kotlin.system.exitProcess(0)
}
