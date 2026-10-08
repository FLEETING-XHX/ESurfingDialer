package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.createProbeHttpClient
import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic data only: confidentiality, HTTP boundaries and cleanup are the assertions. */
object PortalDiagnosticRegression {
    private var checks = 0
    private const val secret = "fixture-sensitive-never-print"
    private const val auth = "http://auth.test/auth.cgi"
    private const val ticket = "http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=fixture-sensitive-never-print"
    private val fields = "<auth-url><![CDATA[$auth]]></auth-url><ticket-url><![CDATA[$ticket]]></ticket-url>"
    private fun page(xml: String) = "<!--//config.campus.js.chinatelecom.com\n$xml\n//config.campus.js.chinatelecom.com-->"
    private fun verify(label: String, value: Boolean) { check(value) { label }; checks++ }
    private fun privateOutput(lines: List<String>): Boolean {
        val text = lines.joinToString("\n")
        return !text.contains(secret) && !text.contains("http://") && !text.contains("10.1.2.") &&
            !text.contains("private-tag") && !text.contains("private-attribute") && !text.contains("private-entity") && !text.contains("private-child") &&
            !text.contains("StackTrace") && !text.contains("Exception")
    }

    fun run(): Int {
        structureAndPrivacy()
        attributeBlindSpot()
        fieldSummaries()
        cliBoundaries()
        networkBoundaries()
        return checks
    }

    private fun structureAndPrivacy() {
        val valid = PortalDiagnostic.describe(page("<private-tag private-attribute=\"$secret\"><!--$secret-->$fields</private-tag>"))
        verify("diagnostic valid config category", valid.any { it.contains("code=PORTAL_CONFIG_OK") })
        verify("diagnostic known tags and opaque unknown tags", valid.any {
            it.contains("authOpen=1 authClose=1 ticketOpen=1 ticketClose=1") && it.contains("otherOpen=1 otherClose=1") && it.contains("attributes=1")
        })
        verify("diagnostic CDATA and comments counted without values", valid.any { it.contains("cdata=2") && it.contains("comments=1") })
        verify("diagnostic valid structure output never leaks", privateOutput(valid))

        val invalid = PortalDiagnostic.describe(page("<private-tag>$fields"))
        verify("diagnostic malformed XML has fixed code and stage", invalid.any { it.contains("code=PORTAL_CONFIG_XML_INVALID") && it.contains("xmlStage=XML_PARSE") })
        verify("diagnostic malformed XML position is numeric", invalid.any {
            Regex("xmlLine=[1-9][0-9]*").containsMatchIn(it) && Regex("xmlColumn=[1-9][0-9]*").containsMatchIn(it)
        })
        verify("diagnostic malformed XML never renders parser message", privateOutput(invalid))

        val entity = PortalDiagnostic.describe(page(fields.replace("<![CDATA[$auth]]>", "&private-entity;")))
        verify("diagnostic entity name hidden but category retained", entity.any { it.contains("textUnknownEntities=1") } && privateOutput(entity))
        val raw = PortalDiagnostic.describe(page("<other>&bad &key=value &amp; &#65; &#x41;</other>$fields"))
        verify("diagnostic distinguishes raw query and XML entity structure", raw.any {
            it.contains("textKnownEntities=1 textNumericEntities=2 textUnknownEntities=0 textQueryAmpersands=1 textRawAmpersands=1")
        })
        val cdata = PortalDiagnostic.describe(page("<other><![CDATA[&private-entity; $secret]]></other>$fields"))
        verify("diagnostic CDATA contents are never scanned as entity markup", cdata.any { it.contains("textUnknownEntities=0") } && privateOutput(cdata))
        val truncated = PortalDiagnostic.describe(page("<"))
        verify("diagnostic truncated markup is safely counted", truncated.any { it.contains("truncatedMarkup=1") })
        val controls = PortalDiagnostic.describe(page("<other>\u0001\uFFFD</other>$fields"))
        verify("diagnostic controls and decoding replacement counts", controls.any { it.contains("invalidControls=1 replacementChars=1") })
        val tooLarge = PortalDiagnostic.describe(secret + "x".repeat(128 * 1024))
        verify("diagnostic oversized body rejected without preview", tooLarge.size == 1 && tooLarge[0].contains("PORTAL_BODY_TOO_LARGE") && privateOutput(tooLarge))
        val ampersands = PortalDiagnostic.describe(page("<other>${"&".repeat(32 * 1024)}</other>$fields"))
        verify("diagnostic bounds a large raw ampersand sequence without rendering it", ampersands.any {
            it.contains("textRawAmpersands=32768")
        } && privateOutput(ampersands))
    }

    private fun attributeBlindSpot() {
        val attribute = "$secret &bad &key=value &amp; &#65; &#x41; &private-entity; <raw"
        val lines = PortalDiagnostic.describe(page("<private-tag private-attribute=\"$attribute\">$fields</private-tag>"))
        verify("diagnostic quoted attribute entities and raw markup counted separately", lines.any {
            it.contains("attributeKnownEntities=1 attributeNumericEntities=2 attributeUnknownEntities=1 attributeQueryAmpersands=1 attributeRawAmpersands=1 attributeRawLessThan=1")
        })
        verify("diagnostic attribute ampersands never mislabeled as text", lines.any {
            it.contains("textKnownEntities=0 textNumericEntities=0 textUnknownEntities=0 textQueryAmpersands=0 textRawAmpersands=0")
        })
        verify("diagnostic attribute blind spot details never render values or names", privateOutput(lines))
        verify("diagnostic local field recovery does not accept malformed document", lines.any {
            it.contains("code=PORTAL_CONFIG_XML_INVALID")
        } && lines.any { it.startsWith("FIELD kind=AUTH_URL") && it.contains("endpointPath=AUTH_CGI") })
        val singleQuoted = PortalDiagnostic.describe(page("<private-tag private-attribute='$attribute'>$fields</private-tag>"))
        verify("diagnostic both attribute quote styles counted", singleQuoted.any { it.contains("attributeRawAmpersands=1 attributeRawLessThan=1") } && privateOutput(singleQuoted))
        val declaration = PortalDiagnostic.describe(page("<!DOCTYPE root SYSTEM 'file:///$secret&private-entity;'><root>$fields</root>"))
        verify("diagnostic declaration quotes are not counted as quoted attributes", declaration.any {
            it.contains("attributeUnknownEntities=0 attributeQueryAmpersands=0 attributeRawAmpersands=0")
        } && privateOutput(declaration))
        val many = PortalDiagnostic.describe(page("<root private-attribute=\"${"&".repeat(32 * 1024)}\">$fields</root>"))
        verify("diagnostic attribute scan handles a bounded long ampersand sequence", many.any {
            it.contains("attributeRawAmpersands=32768")
        } && privateOutput(many))
    }

    private fun fieldSummaries() {
        val first = "<auth-url private-attribute=\"$secret\"><![CDATA[$auth]]></auth-url>"
        val duplicate = "<auth-url>http://auth.test/auth.c&#103;i</auth-url>"
        val lines = PortalDiagnostic.describe(page("<private-tag>$first<private-child>$duplicate</private-child>${fields.substringAfter("</auth-url>")}</private-tag>"))
        val authLines = lines.filter { it.startsWith("FIELD kind=AUTH_URL ") }
        verify("diagnostic equivalent fields accepted and occurrence counts preserved", lines.any {
            it.contains("code=PORTAL_CONFIG_OK")
        } && lines.contains("FIELD_TOTAL kind=AUTH_URL count=2 shown=2") && authLines.size == 2)
        verify("diagnostic field parents use opaque shared node IDs", authLines[0].contains("ordinal=1 node=T2 parentDepth=1 parents=T1") &&
            authLines[1].contains("ordinal=2 node=T4 parentDepth=2 parents=T1/T3"))
        verify("diagnostic fields classify CDATA text and attribute counts", authLines[0].contains("attributes=1 content=CDATA") &&
            authLines[1].contains("attributes=0 content=TEXT"))
        verify("diagnostic normalized equivalent values share numeric group", authLines.all {
            it.contains("endpointPath=AUTH_CGI valueGroup=1")
        })
        verify("diagnostic field summaries do not expose URLs names values or hashes", privateOutput(lines) && lines.none { it.contains("hash=") })

        val other = PortalDiagnostic.describe(page("<root>$first<auth-url><![CDATA[http://other.test/landing?password=$secret]]></auth-url></root>"))
        verify("diagnostic unrelated page endpoint is only a fixed OTHER category", other.any {
            it.startsWith("FIELD kind=AUTH_URL ordinal=2") && it.contains("endpointPath=OTHER valueGroup=2")
        } && privateOutput(other))
        val mixed = PortalDiagnostic.describe(page("<root><auth-url>http://<![CDATA[auth.test]]>/auth.cgi</auth-url></root>"))
        verify("diagnostic mixed field content recovers only local path category", mixed.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("content=MIXED endpointPath=AUTH_CGI valueGroup=1")
        } && privateOutput(mixed))
        val empty = PortalDiagnostic.describe(page("<auth-url> \n</auth-url>"))
        verify("diagnostic empty field has no equality value", empty.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("parents=ROOT attributes=0 content=EMPTY endpointPath=EMPTY valueGroup=0")
        })
        val child = PortalDiagnostic.describe(page("<auth-url><private-child>$auth</private-child></auth-url>"))
        verify("diagnostic child markup never becomes a recovered endpoint", child.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("content=CHILD_MARKUP endpointPath=UNKNOWN valueGroup=0")
        } && privateOutput(child))
        val invalid = PortalDiagnostic.describe(page("<auth-url>$secret</auth-url>"))
        verify("diagnostic invalid URL is a fixed category without its value", invalid.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("content=TEXT endpointPath=INVALID valueGroup=1")
        } && privateOutput(invalid))
        val unclosed = PortalDiagnostic.describe(page("<private-tag>$fields"))
        verify("diagnostic unclosed parent chain remains UNKNOWN", unclosed.filter { it.startsWith("FIELD ") }.all {
            it.contains("parents=UNKNOWN") && it.contains("content=UNKNOWN endpointPath=UNKNOWN valueGroup=0")
        } && privateOutput(unclosed))
        val entity = PortalDiagnostic.describe(page("<auth-url>&private-entity;</auth-url>"))
        verify("diagnostic unknown field entity cannot yield a value equality group", entity.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("content=UNKNOWN endpointPath=UNKNOWN valueGroup=0")
        } && privateOutput(entity))
        val mismatch = PortalDiagnostic.describe(page("<root><auth-url>$auth</ticket-url></root>"))
        verify("diagnostic mismatched field closure remains UNKNOWN", mismatch.any {
            it.startsWith("FIELD kind=AUTH_URL") && it.contains("content=UNKNOWN endpointPath=UNKNOWN valueGroup=0")
        })
        val deep = PortalDiagnostic.describe(page("<x>".repeat(65) + fields + "</x>".repeat(65)))
        verify("diagnostic parent chain is bounded and excessive depth UNKNOWN", deep.filter { it.startsWith("FIELD ") }.all {
            it.contains("parentDepth=65 parents=UNKNOWN") && it.contains("valueGroup=0")
        })
        val many = PortalDiagnostic.describe(page(fields.repeat(32)))
        verify("diagnostic limits each field kind to eight summaries", many.count { it.startsWith("FIELD kind=AUTH_URL ") } == 8 &&
            many.count { it.startsWith("FIELD kind=TICKET_URL ") } == 8 && many.contains("FIELD_TOTAL kind=AUTH_URL count=32 shown=8") &&
            many.contains("FIELD_TOTAL kind=TICKET_URL count=32 shown=8") && privateOutput(many))
        val repeatedMismatch = PortalDiagnostic.describe(page("<x>".repeat(1000) + "</y>".repeat(1000)))
        verify("diagnostic repeated mismatches retain bounded output", repeatedMismatch.size <= 5 && privateOutput(repeatedMismatch))
    }

    private fun cliBoundaries() {
        val lines = mutableListOf<String>()
        val invalid = PortalDiagnostic.runCli(arrayOf("--url", "http://$secret"), ByteArrayInputStream(ByteArray(0)), lines::add)
        verify("diagnostic rejects arbitrary arguments without echo", invalid == 2 && lines == listOf("DIAGNOSTIC code=INVALID_ARGUMENTS"))
        lines.clear()
        val broken = object : InputStream() { override fun read(): Int = throw IllegalStateException(secret) }
        val failed = PortalDiagnostic.runCli(arrayOf("--stdin"), broken, lines::add)
        verify("diagnostic catches IO exception without its message", failed == 1 && lines.last() == "DIAGNOSTIC code=DIAGNOSTIC_FAILED" && privateOutput(lines))
        lines.clear()
        val input = ByteArrayInputStream(page(fields).toByteArray())
        val ok = PortalDiagnostic.runCli(arrayOf("--stdin"), input, lines::add)
        verify("diagnostic offline CLI labels result as version two structure only", ok == 0 && lines.first().contains("mode=OFFLINE_STRUCTURE version=2") && lines.last().contains("category=STRUCTURE_ONLY") && privateOutput(lines))
        lines.clear()
        PortalDiagnostic.runCli(arrayOf("--stdin"), ByteArrayInputStream("x".repeat(128 * 1024 + 2).toByteArray()), lines::add)
        verify("diagnostic stdin read is bounded", lines.any { it == "CONFIG code=PORTAL_BODY_TOO_LARGE bodyBytes=131073" })
    }

    private fun networkBoundaries() {
        val requests = AtomicInteger()
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val userAgents = Collections.synchronizedList(mutableListOf<String?>())
        val credentialHeaders = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val valid = page("<private-tag private-attribute=\"$secret\">$fields</private-tag>")
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            methods += exchange.requestMethod
            userAgents += exchange.requestHeaders.getFirst("User-Agent")
            for (header in listOf("Cookie", "Authorization", "Client-ID", "Algo-ID", "CDC-Checksum"))
                if (exchange.requestHeaders.getFirst(header) != null) credentialHeaders.incrementAndGet()
            exchange.responseHeaders.add("Set-Cookie", "private=$secret")
            exchange.responseHeaders.add("Content-Type", "text/html; private=$secret")
            when (exchange.requestURI.path) {
                "/probe" -> { exchange.responseHeaders.add("Location", "/hop?secret=$secret"); exchange.sendResponseHeaders(302, -1) }
                "/hop" -> { exchange.responseHeaders.add("Location", "/portal?secret=$secret"); exchange.sendResponseHeaders(302, -1) }
                else -> {
                    val body = valid.toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.write(body)
                }
            }
            exchange.close()
        }
        server.start()
        val base = createProbeHttpClient(2).newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        val lines = mutableListOf<String>()
        try {
            PortalDiagnostic.runNetwork(listOf("http://portal.test:${server.address.port}/probe"), base, lines::add)
            verify("diagnostic network identifies version two", lines.first() == "DIAGNOSTIC mode=PORTAL_ONLY version=2 loginAttempted=0")
            verify("diagnostic only follows bounded GET discovery", requests.get() == 3 && methods.all { it == "GET" })
            verify("diagnostic matches deployed client UA", userAgents.isNotEmpty() && userAgents.all { it == Constants.USER_AGENT })
            verify("diagnostic never sends cookies or authentication headers", credentialHeaders.get() == 0)
            verify("diagnostic records every HTTP hop safely", lines.count { it.startsWith("HTTP ") } == 3 && privateOutput(lines))
            verify("diagnostic discovered result explicitly says no login", lines.last() == "RESULT category=PORTAL_CONFIGURATION_DISCOVERED source=PORTAL loginAttempted=0")
            verify("diagnostic closes pooled connections and dispatcher", base.connectionPool.connectionCount() == 0 && base.dispatcher.executorService.isTerminated)
        } finally { server.stop(0); base.connectionPool.evictAll(); base.dispatcher.executorService.shutdownNow() }

        val failedBase = createProbeHttpClient(2).newBuilder().addInterceptor { throw IllegalStateException(secret) }.build()
        val failedLines = mutableListOf<String>()
        PortalDiagnostic.runNetwork(listOf("http://portal.test/probe"), failedBase, failedLines::add)
        verify("diagnostic transport exceptions never render secrets", failedLines.last() == "RESULT category=PROBE_FAILED loginAttempted=0" && privateOutput(failedLines))
        verify("diagnostic failed transport still terminates dispatcher", failedBase.dispatcher.executorService.isTerminated)
    }
}
