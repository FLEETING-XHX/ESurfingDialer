package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.*
import com.rsplwe.esurfing.utils.*
import com.sun.net.httpserver.HttpServer
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object AuthenticationRegression {
    private var checks = 0
    private fun verify(label: String, condition: Boolean) {
        check(condition) { label }; checks++
    }
    private fun fails(code: String, action: () -> Unit) {
        try { action(); error("Expected $code") }
        catch (e: AuthenticationFailure) { verify(code, e.code == code) }
    }
    private fun xml(name: String, value: String) = "<response><$name>$value</$name></response>"
    private val config = """<!--//config.campus.js.chinatelecom.com
        <auth-url><![CDATA[http://auth.test/auth.cgi]]></auth-url>
        <ticket-url><![CDATA[http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=test]]></ticket-url>
        //config.campus.js.chinatelecom.com-->""".trimIndent()

    @JvmStatic fun main(args: Array<String>) {
        protocol()
        nativeLifecycle()
        portalHttp()
        clientIntegration()
        checks += PortalRegression.run()
        println("Docker authentication regression: $checks checks passed")
    }

    private fun protocol() {
        verify("CDATA ticket", AuthenticationProtocol.ticket(xml("ticket", "<![CDATA[a&b<c>]]>")) == "a&b<c>")
        val secret = "a&<b>\"'中文"
        verify("XML escaping round trip", AuthenticationProtocol.ticket(xml("ticket", AuthenticationProtocol.escape(secret))) == secret)
        for (body in listOf("", "<response/>", "<response><ticket/></response>", "<response><ticket>x</ticket><ticket>y</ticket></response>", "<response><nested><ticket>x</ticket></nested></response>", "<response><ticket><x/></ticket></response>")) {
            fails(if (body.isEmpty()) "TICKET_XML_INVALID" else "TICKET_FIELDS_INVALID") { AuthenticationProtocol.ticket(body) }
        }
        fails("TICKET_XML_INVALID") { AuthenticationProtocol.ticket("<!DOCTYPE response [<!ENTITY x SYSTEM 'file:///never-read'>]><response><ticket>&x;</ticket></response>") }
        fails("TICKET_XML_TOO_LARGE") { AuthenticationProtocol.ticket("x".repeat(AuthenticationProtocol.MAX_XML_CHARS + 1)) }
        fails("HEARTBEAT_FIELDS_INVALID") { AuthenticationProtocol.heartbeat("<response><error>denied</error></response>") }
        for (value in listOf("0", "-1", "+1", "bad", "1.5", "9223372036854775808", "１２"))
            fails("HEARTBEAT_INTERVAL_INVALID") { AuthenticationProtocol.heartbeat(xml("interval", value)) }
        verify("minimum interval", AuthenticationProtocol.heartbeat(xml("interval", "1")) == 5L)
        verify("maximum interval", AuthenticationProtocol.heartbeat(xml("interval", "999999")) == RuntimeConfig.heartbeatIntervalMaxSeconds)
        val login = "<response><keep-url>http://keep.test/keep?a=1&amp;b=2</keep-url><term-url><![CDATA[http://term.test/term]]></term-url><keep-retry>30</keep-retry></response>"
        verify("login URL entities", AuthenticationProtocol.login(login).keepUrl.endsWith("a=1&b=2"))
        fails("LOGIN_FIELDS_INVALID") { AuthenticationProtocol.login(login.replace("<keep-retry>30</keep-retry>", "")) }
        fails("LOGIN_URL_INVALID") { AuthenticationProtocol.login(login.replace("http://term.test/term", "file:///tmp/secret")) }
        fails("LOGIN_URL_INVALID") { AuthenticationProtocol.login(login.replace("http://term.test/term", "http://127.0.0.1/term")) }
        var closed = false
        val unknownLength = object : ResponseBody() {
            private val buffer = Buffer().write(ByteArray(18))
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = buffer
            override fun close() { closed = true; super.close() }
        }
        fails("TEST_BODY_TOO_LARGE") { readBoundedBody(unknownLength, "TEST", 16) }
        verify("chunked body closed", closed)
        fails("TEST_BODY_TOO_LARGE") { readBoundedBody(ByteArray(18).toResponseBody(), "TEST", 16) }
        fails("TEST_BODY_EMPTY") { readBoundedBody("".toResponseBody(), "TEST", 16) }
        verify("exact body limit", readBoundedBody(ByteArray(16).toResponseBody(), "TEST", 16).size == 16)
    }

    private fun nativeLifecycle() {
        var algorithmCalls = 0; var frees = 0
        fails("NATIVE_SESSION_REJECTED") { NativeSessionLoader.load({ 0 }, { algorithmCalls++; "unused" }, { frees++ }) }
        verify("zero handle never passed to aid/free", algorithmCalls == 0 && frees == 0)
        fails("NATIVE_LOAD_EXCEPTION") { NativeSessionLoader.load({ error("secret") }, { "unused" }, { frees++ }) }
        fails("NATIVE_ALGO_ID_INVALID") { NativeSessionLoader.load({ 9 }, { "invalid" }, { frees++ }) }
        verify("bad aid releases handle", frees == 1)
        fails("NATIVE_ALGO_ID_INVALID") { NativeSessionLoader.load({ 9 }, { error("secret") }, { frees++; error("cleanup") }) }
        verify("failed cleanup attempted once", frees == 2)
        val loaded = NativeSessionLoader.load({ 7 }, { "12345678-1234-1234-1234-123456789abc" }, { error("unexpected") })
        verify("valid native metadata", loaded.first == 7L)
        val handle = NativeSessionHandle(7) { frees++ }
        verify("native use", handle.use { it } == 7L)
        handle.free(); handle.free()
        verify("idempotent free", frees == 3)
        fails("NATIVE_SESSION_CLOSED") { handle.use { } }
        val failedFree = NativeSessionHandle(8) { frees++; error("free failed") }
        try { failedFree.free() } catch (_: IllegalStateException) { }
        failedFree.free()
        verify("failed release not repeated", frees == 4)
        fails("NATIVE_SESSION_CLOSED") { failedFree.use { } }
        val entered = CountDownLatch(1); val leave = CountDownLatch(1); val released = CountDownLatch(1)
        val concurrent = NativeSessionHandle(12) { released.countDown() }
        val useThread = thread { concurrent.use { entered.countDown(); check(leave.await(3, TimeUnit.SECONDS)) } }
        check(entered.await(3, TimeUnit.SECONDS))
        val freeThread = thread { concurrent.free() }
        verify("free waits for use", !released.await(100, TimeUnit.MILLISECONDS))
        leave.countDown(); useThread.join(3000); freeThread.join(3000)
        verify("free after use completes", released.count == 0L && !useThread.isAlive && !freeThread.isAlive)
    }

    private fun portalHttp() {
        verify("inline portal config", PortalConfiguration.parse(config)?.userIp == "10.1.2.3")
        fails("PORTAL_CONFIG_DUPLICATE") { PortalConfiguration.parse(config + config) }
        fails("PORTAL_AUTH_URL_DUPLICATE") { PortalConfiguration.parse(config.replace("<auth-url>", "<auth-url>http://a/auth.cgi</auth-url><auth-url>")) }
        fails("PORTAL_CONFIG_TRUNCATED") { PortalConfiguration.parse(config.substringBefore("//config.campus.js.chinatelecom.com-->")) }
        fails("PORTAL_IP_INVALID") { PortalConfiguration.parse(config.replace("10.1.2.3", "999.1.2.3")) }
        fails("PORTAL_ENDPOINT_PATH_INVALID") { PortalConfiguration.parse(config.replace("auth.cgi", "evil.cgi")) }
        fails("PORTAL_AUTH_URL_INVALID") { PortalConfiguration.parse(config.replace("auth.test", "localhost")) }
        verify("valid legacy fallback", PortalConfiguration.legacy("10.2.3.4", "10.2.3.1").source == "legacy_fallback")
        fails("PORTAL_IP_INVALID") { PortalConfiguration.legacy("", "") }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when (path) {
                "/probe", "/landing", "/loop" -> {
                    exchange.responseHeaders.add("Location", if (path == "/probe") "/landing" else if (path == "/landing") "/config" else "/loop")
                    exchange.sendResponseHeaders(302, -1)
                }
                else -> {
                    val content = when (path) {
                        "/config" -> config
                        "/bad" -> config.replace("10.1.2.3", "999.1.2.3")
                        "/large" -> "x".repeat(128 * 1024 + 1)
                        else -> "ordinary page"
                    }.toByteArray()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(content)
                }
            }
            exchange.close()
        }
        server.start()
        val client = createProbeHttpClient(2).newBuilder().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val base = "http://portal.test:${server.address.port}"
        try {
            val probe = checkConnectivity(listOf("$base/probe"), client)
            verify("first redirect missing IP preserved", probe.status == ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP && probe.portalUrl == "$base/landing")
            val discovered = PortalConfiguration.discover(probe.portalUrl, probe.portalBody, "", "", client)
            verify("redirect chain discovers dynamic endpoints", discovered.authUrl == "http://auth.test/auth.cgi" && discovered.ticketUrl.startsWith("http://ticket.test/"))
            val inline = checkConnectivity(listOf("$base/config"), client)
            verify("200 page config detected", inline.status == ConnectivityStatus.IS_REDIRECTS_FOUND_IP && inline.portalBody == config)
            fails("PORTAL_IP_INVALID") { PortalConfiguration.discover("$base/bad", null, "10.0.0.2", "10.0.0.1", client) }
            fails("PORTAL_BODY_TOO_LARGE") { PortalConfiguration.discover("$base/large", null, "10.0.0.2", "10.0.0.1", client) }
            fails("PORTAL_REDIRECT_LIMIT") { PortalConfiguration.discover("$base/loop", null, "10.0.0.2", "10.0.0.1", client) }
            verify("oversized probe never online", checkConnectivity(listOf("$base/large"), client).status == ConnectivityStatus.REQUEST_ERROR)
            fails("PORTAL_IP_INVALID") { PortalConfiguration.discover("$base/plain?wlanuserip=10.4.5.6&wlanuserip=10.9.9.9&wlanacip=10.4.5.1", null, "10.0.0.2", "10.0.0.1", client) }
            verify("later redirect IP fallback", PortalConfiguration.discover("$base/plain?wlanuserip=10.4.5.6&wlanacip=10.4.5.1", null, "", "", client).userIp == "10.4.5.6")
        } finally { server.stop(0); client.connectionPool.evictAll() }
    }

    private fun clientIntegration() {
        States.isRunning = true; States.clientId = "device&original"; States.macAddress = "00:01:02:03:04:05"
        States.portalCandidate = PortalCandidate(portalBody = config)
        HealthStatus.resetSession()
        var frees = 0; var bootstrap = true; var badHeartbeat = false
        data class Sent(val url: String, val body: String, val headers: Map<String, String>)
        val requests = mutableListOf<Sent>()
        val fake = object : AuthenticationSession {
            override fun getSessionId() = 17L
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
            override fun free() { frees++ }
        }
        val client = Client(Options("user<&", "password<&\"'"), {
            States.algoId = "12345678-1234-1234-1234-123456789abc"; fake
        }, { url, payload, headers ->
            requests += Sent(url, payload, headers.toMap())
            val response = when {
                bootstrap -> { bootstrap = false; "mock bootstrap" }
                url.startsWith("http://ticket.test/") -> xml("ticket", "t&amp;&lt;")
                url.startsWith("http://auth.test/") -> "<response><keep-url>http://keep.test/keep</keep-url><term-url>http://term.test/term</term-url><keep-retry>30</keep-retry></response>"
                url.startsWith("http://keep.test/") -> if (badHeartbeat) "<response><error>denied</error></response>" else xml("interval", "30")
                else -> "terminated"
            }
            NetResult.Success(response.toResponseBody())
        })
        val auth = Client::class.java.getDeclaredMethod("authorization", AuthorizationTrigger::class.java).apply { isAccessible = true }
        val heartbeat = Client::class.java.getDeclaredMethod("heartbeat", String::class.java).apply { isAccessible = true }
        try {
            auth.invoke(client, AuthorizationTrigger.INITIAL)
            verify("Client login confirmed by strict heartbeat", HealthStatus.authenticated && requests.count { it.url.startsWith("http://keep.test/") } == 1)
            verify("Client dynamic auth/ticket destinations", requests.take(3).map { it.url.substringBefore('?') } == listOf("http://ticket.test/ticket.cgi", "http://ticket.test/ticket.cgi", "http://auth.test/auth.cgi"))
            verify("Client credential escaping", requests[2].body.contains("<userid>user&lt;&amp;</userid>") && requests[2].body.contains("<passwd>password&lt;&amp;&quot;&apos;</passwd>"))
            rememberPortal(NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_FOUND_IP, "10.9.9.9", "10.9.9.1"))
            States.clientId = "new-device"; States.macAddress = "new-mac"
            heartbeat.invoke(client, States.ticket)
            verify("active IP remains captured", requests.last().body.contains("<ipv4>10.1.2.3</ipv4>") && !requests.last().body.contains("10.9.9.9"))
            verify("active device/header remain captured", requests.last().headers["Client-ID"] == "device&original" && requests.last().body.contains("<client-id>device&amp;original</client-id>"))
            badHeartbeat = true
            try { heartbeat.invoke(client, States.ticket); error("Bad heartbeat accepted") }
            catch (e: InvocationTargetException) { verify("Client rejects malformed heartbeat", (e.cause as? AuthenticationFailure)?.code == "HEARTBEAT_FIELDS_INVALID") }
            client.shutdown(); client.shutdown()
            verify("Client shutdown frees once", frees == 1 && client.session == null && !HealthStatus.authenticated)
            verify("termination uses old session IP", requests.last().url == "http://term.test/term" && requests.last().body.contains("<ipv4>10.1.2.3</ipv4>"))
        } finally { client.shutdown() }
        States.portalCandidate = PortalCandidate(portalBody = config)
        bootstrap = true
        val rejected = Client(Options("mock-user", "mock-password"), { fake }, { url, _, _ ->
            NetResult.Success(when {
                bootstrap -> { bootstrap = false; "mock bootstrap" }
                url.startsWith("http://ticket.test/") -> xml("ticket", "mock-ticket")
                url.startsWith("http://auth.test/") -> "<response><keep-url>http://keep.test/keep</keep-url><term-url>http://term.test/term</term-url><keep-retry>30</keep-retry></response>"
                url.startsWith("http://keep.test/") -> "<response><error>denied</error></response>"
                else -> "terminated"
            }.toResponseBody())
        }, { NetworkConnectivityResult(ConnectivityStatus.SUCCESS) })
        try {
            auth.invoke(rejected, AuthorizationTrigger.INITIAL)
            verify("online probes cannot confirm malformed keep", !HealthStatus.authenticated && rejected.session == null)
            verify("failed confirmation frees session", frees == 2)
        } finally { rejected.shutdown() }
    }
}
