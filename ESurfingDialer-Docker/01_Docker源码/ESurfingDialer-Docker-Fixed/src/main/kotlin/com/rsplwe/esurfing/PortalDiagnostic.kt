package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.createProbeHttpClient
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.checkConnectivity
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** A GET-only one-shot probe. Every rendered value is a count or a fixed category. */
object PortalDiagnostic {
    private const val LIMIT = 128 * 1024
    private const val START = "<!--//config.campus.js.chinatelecom.com"
    private const val END = "//config.campus.js.chinatelecom.com-->"
    private val querySeparator = Regex("&[A-Za-z_][A-Za-z0-9_.~-]*=")
    private val namedEntity = Regex("&[A-Za-z_:][A-Za-z0-9_.:-]*;")
    private val numericEntity = Regex("&#(?:[0-9]+|x[0-9a-fA-F]+);")
    private val knownEntities = listOf("&amp;", "&lt;", "&gt;", "&quot;", "&apos;")
    private val codes = setOf(
        "PORTAL_BODY_TOO_LARGE", "PORTAL_CONFIG_DUPLICATE", "PORTAL_CONFIG_TRUNCATED",
        "PORTAL_CONFIG_XML_INVALID", "PORTAL_XML_PARSER_SETUP_FAILED", "PORTAL_XML_PARSER_FAILED",
        "PORTAL_AUTH_URL_MISSING", "PORTAL_AUTH_URL_DUPLICATE", "PORTAL_AUTH_URL_EMPTY",
        "PORTAL_AUTH_URL_INVALID", "PORTAL_AUTH_URL_STRUCTURE_INVALID",
        "PORTAL_TICKET_URL_MISSING", "PORTAL_TICKET_URL_DUPLICATE", "PORTAL_TICKET_URL_EMPTY",
        "PORTAL_TICKET_URL_INVALID", "PORTAL_TICKET_URL_STRUCTURE_INVALID",
        "PORTAL_ENDPOINT_PATH_INVALID", "PORTAL_IP_INVALID", "PORTAL_INVALID_URL",
        "PORTAL_REDIRECT_INVALID", "PORTAL_REDIRECT_LIMIT", "PORTAL_HTTP_ERROR", "PORTAL_TRANSPORT_ERROR",
    )

    @JvmStatic fun main(args: Array<String>) {
        exitProcess(runCli(args, System.`in`, ::println))
    }

    /** No file paths, arbitrary URL arguments, environment dumps, or exception messages are accepted. */
    fun runCli(args: Array<String>, input: InputStream, emit: (String) -> Unit): Int {
        return try {
            when {
                args.isEmpty() -> runNetwork(RuntimeConfig.networkCheckUrls, createProbeHttpClient(10), emit)
                args.contentEquals(arrayOf("--stdin")) -> {
                    emit("DIAGNOSTIC mode=OFFLINE_STRUCTURE version=2 loginAttempted=0")
                    val bytes = input.readNBytes(LIMIT + 1)
                    if (bytes.size > LIMIT) emit("CONFIG code=PORTAL_BODY_TOO_LARGE bodyBytes=${bytes.size}")
                    else describe(bytes.toString(Charsets.UTF_8), bytes.size).forEach(emit)
                    emit("RESULT category=STRUCTURE_ONLY loginAttempted=0")
                }
                else -> { emit("DIAGNOSTIC code=INVALID_ARGUMENTS"); return 2 }
            }
            0
        } catch (_: Throwable) {
            emit("DIAGNOSTIC code=DIAGNOSTIC_FAILED")
            1
        }
    }

    /** Injected clients are for offline regressions; the command uses the deployed probe defaults. */
    fun runNetwork(urls: List<String>, base: OkHttpClient, emit: (String) -> Unit) {
        var requests = 0
        val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .addInterceptor { chain ->
                if (chain.request().method != "GET" || ++requests > 11)
                    throw AuthenticationFailure("DIAGNOSTIC_REQUEST_REJECTED")
                val response = chain.proceed(chain.request())
                try {
                    val bytes = response.peekBody(LIMIT.toLong() + 1).bytes()
                    emit("HTTP request=$requests status=${response.code} mediaType=${PortalMediaType.fromHeader(response.header("Content-Type"))} bodyBytes=${bytes.size}")
                    if (bytes.size > LIMIT) emit("CONFIG code=PORTAL_BODY_TOO_LARGE bodyBytes=${bytes.size}")
                    else describe(bytes.toString(Charsets.UTF_8), bytes.size).forEach(emit)
                    response
                } catch (failure: Throwable) { response.close(); throw failure }
            }.build()
        try {
            emit("DIAGNOSTIC mode=PORTAL_ONLY version=2 loginAttempted=0")
            val probe = checkConnectivity(urls.take(8), client)
            emit("PROBE status=${probe.status} portalUrlPresent=${if (probe.portalUrl.isNullOrBlank()) 0 else 1} cachedBodyPresent=${if (probe.portalBody == null) 0 else 1}")
            when (probe.status) {
                ConnectivityStatus.SUCCESS -> emit("RESULT category=PROBE_ONLINE loginAttempted=0")
                ConnectivityStatus.IS_REDIRECTS_FOUND_IP, ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP -> {
                    try {
                        val endpoints = PortalConfiguration.discover(probe.portalUrl, probe.portalBody,
                            probe.userIp.orEmpty(), probe.acIp.orEmpty(), client)
                        val source = when (endpoints.source) { "portal" -> "PORTAL"; "legacy_fallback" -> "LEGACY_FALLBACK"; else -> "OTHER" }
                        emit("RESULT category=PORTAL_CONFIGURATION_DISCOVERED source=$source loginAttempted=0")
                    } catch (failure: AuthenticationFailure) {
                        emit("RESULT category=PORTAL_CONFIGURATION_FAILED code=${safeCode(failure.code)} loginAttempted=0" +
                            (failure.portalDiagnostics?.let { " ${it.summary()}" } ?: ""))
                    }
                }
                else -> emit("RESULT category=PROBE_FAILED loginAttempted=0")
            }
        } catch (_: Throwable) {
            emit("RESULT category=DIAGNOSTIC_FAILED loginAttempted=0")
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    /** Pure offline entry point: lexical structure is explicitly distinct from parsed field counts. */
    fun describe(body: String, bodyBytes: Int = -1): List<String> {
        if (body.length > LIMIT) return listOf("CONFIG code=PORTAL_BODY_TOO_LARGE bodyChars=${body.length} bodyBytes=$bodyBytes")
        val lines = mutableListOf<String>()
        try {
            val endpoints = PortalConfiguration.parse(body)
            val blocks = Regex(Regex.escape(START)).findAll(body).count()
            val code = if (endpoints == null) "PORTAL_CONFIG_ABSENT" else "PORTAL_CONFIG_OK"
            lines += "CONFIG code=$code bodyChars=${body.length} bodyBytes=$bodyBytes blocks=$blocks"
        } catch (failure: AuthenticationFailure) {
            lines += "CONFIG code=${safeCode(failure.code)}" +
                (failure.portalDiagnostics?.copy(bodyBytes = bodyBytes)?.let { " ${it.summary()}" } ?: "")
        } catch (_: Throwable) {
            lines += "CONFIG code=DIAGNOSTIC_PARSE_FAILED bodyChars=${body.length} bodyBytes=$bodyBytes"
        }
        val marker = body.indexOf(START)
        if (marker >= 0) {
            val begin = marker + START.length
            val end = body.indexOf(END, begin)
            if (end >= 0) {
                val xml = body.substring(begin, end)
                lines += structure(xml)
                lines += PortalFieldStructure.describe(xml)
            }
        }
        return lines
    }

    private fun safeCode(code: String): String = if (code in codes) code else "OTHER_ERROR"

    /** Tag and attribute names, entity names, text and URL values never leave this scanner. */
    private fun structure(xml: String): String {
        var authOpen = 0; var authClose = 0; var ticketOpen = 0; var ticketClose = 0
        var otherOpen = 0; var otherClose = 0; var emptyTags = 0; var attributes = 0
        var cdata = 0; var cdataChars = 0; var comments = 0; var commentChars = 0
        var processing = 0; var declarations = 0; var xmlDeclarations = 0; var doctypes = 0
        var rawAmp = 0; var queryAmp = 0; var knownEntity = 0; var unknownEntity = 0; var numeric = 0
        var attributeRawAmp = 0; var attributeQueryAmp = 0; var attributeKnownEntity = 0
        var attributeUnknownEntity = 0; var attributeNumeric = 0; var attributeRawLessThan = 0
        var textChars = 0; var truncated = 0; var depth = 0; var maxDepth = 0; var at = 0
        while (at < xml.length) {
            when {
                xml.startsWith("<![CDATA[", at) -> {
                    cdata++
                    val end = xml.indexOf("]]>", at + 9)
                    cdataChars += (if (end < 0) xml.length else end) - at - 9
                    if (end < 0) truncated++
                    at = if (end < 0) xml.length else end + 3
                }
                xml.startsWith("<!--", at) -> {
                    comments++
                    val end = xml.indexOf("-->", at + 4)
                    commentChars += (if (end < 0) xml.length else end) - at - 4
                    if (end < 0) truncated++
                    at = if (end < 0) xml.length else end + 3
                }
                xml.startsWith("<?", at) -> {
                    processing++
                    if (xml.startsWith("<?xml", at) && xml.getOrNull(at + 5)?.let { it.isWhitespace() || it == '?' } == true) xmlDeclarations++
                    val end = xml.indexOf("?>", at + 2)
                    if (end < 0) truncated++
                    at = if (end < 0) xml.length else end + 2
                }
                xml[at] == '<' -> {
                    val begin = at
                    val elementTag = !xml.startsWith("<!", begin)
                    var quote: Char? = null; var brackets = 0; var found = false
                    at++
                    while (at < xml.length) {
                        val c = xml[at++]
                        if (quote != null) {
                            if (c == quote) quote = null
                            else if (elementTag && c == '<') attributeRawLessThan++
                            else if (elementTag && c == '&') {
                                val ampAt = at - 1
                                when {
                                    knownEntities.any { xml.startsWith(it, ampAt) } -> attributeKnownEntity++
                                    numericEntity.matchAt(xml, ampAt) != null -> attributeNumeric++
                                    querySeparator.matchAt(xml, ampAt) != null -> attributeQueryAmp++
                                    namedEntity.matchAt(xml, ampAt) != null -> attributeUnknownEntity++
                                    else -> attributeRawAmp++
                                }
                            }
                        }
                        else when (c) {
                            '\'', '"' -> quote = c
                            '[' -> brackets++
                            ']' -> if (brackets > 0) brackets--
                            '=' -> if (elementTag) attributes++
                            '>' -> if (brackets == 0) { found = true; break }
                        }
                    }
                    if (!found) truncated++
                    if (xml.startsWith("<!", begin)) {
                        declarations++
                        if (xml.regionMatches(begin, "<!DOCTYPE", 0, 9, true)) doctypes++
                    } else {
                        val close = xml.getOrNull(begin + 1) == '/'
                        val nameStart = (begin + if (close) 2 else 1).coerceAtMost(at)
                        var nameEnd = nameStart
                        while (nameEnd < at && !xml[nameEnd].isWhitespace() && xml[nameEnd] != '>' && xml[nameEnd] != '/') nameEnd++
                        val name = xml.substring(nameStart.coerceAtMost(at), nameEnd)
                        when (name) {
                            "auth-url" -> if (close) authClose++ else authOpen++
                            "ticket-url" -> if (close) ticketClose++ else ticketOpen++
                            else -> if (close) otherClose++ else otherOpen++
                        }
                        val empty = found && xml.substring(begin, at - 1).trimEnd().endsWith('/')
                        if (empty) emptyTags++
                        if (close) depth = (depth - 1).coerceAtLeast(0)
                        else if (!empty) { depth++; maxDepth = maxOf(maxDepth, depth) }
                    }
                }
                xml[at] == '&' -> {
                    when {
                        knownEntities.any { xml.startsWith(it, at) } -> knownEntity++
                        numericEntity.matchAt(xml, at) != null -> numeric++
                        querySeparator.matchAt(xml, at) != null -> queryAmp++
                        namedEntity.matchAt(xml, at) != null -> unknownEntity++
                        else -> rawAmp++
                    }
                    textChars++; at++
                }
                else -> { textChars++; at++ }
            }
        }
        val invalidControls = xml.count { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 }
        val replacementChars = xml.count { it == '\uFFFD' }
        return "STRUCTURE category=FIRST_CONFIG_LEXICAL configChars=${xml.length} authOpen=$authOpen authClose=$authClose ticketOpen=$ticketOpen ticketClose=$ticketClose " +
            "otherOpen=$otherOpen otherClose=$otherClose emptyTags=$emptyTags attributes=$attributes cdata=$cdata cdataChars=$cdataChars comments=$comments commentChars=$commentChars " +
            "processingInstructions=$processing xmlDeclarations=$xmlDeclarations declarations=$declarations doctypes=$doctypes textKnownEntities=$knownEntity textNumericEntities=$numeric " +
            "textUnknownEntities=$unknownEntity textQueryAmpersands=$queryAmp textRawAmpersands=$rawAmp " +
            "attributeKnownEntities=$attributeKnownEntity attributeNumericEntities=$attributeNumeric attributeUnknownEntities=$attributeUnknownEntity " +
            "attributeQueryAmpersands=$attributeQueryAmp attributeRawAmpersands=$attributeRawAmp attributeRawLessThan=$attributeRawLessThan " +
            "textChars=$textChars lexicalMaxDepth=$maxDepth truncatedMarkup=$truncated " +
            "invalidControls=$invalidControls replacementChars=$replacementChars"
    }
}

/** Local lexical recovery for diagnostics only. It never supplies endpoints to authentication. */
private object PortalFieldStructure {
    private const val PER_KIND_LIMIT = 8
    private const val DEPTH_LIMIT = 64
    private val name = Regex("[A-Za-z_:][A-Za-z0-9_.:-]*")
    private val entity = Regex("&(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9a-fA-F]+);")
    private val query = Regex("&(?=[A-Za-z_][A-Za-z0-9_.~-]*=)")

    private class Node(val id: Int, val name: String, val attributes: Int, var reliable: Boolean) {
        var closed = false
        var field: Field? = null
    }
    private class Field(val kind: String, val ordinal: Int, val node: Node, val parents: List<Node>, val depth: Int) {
        val value = StringBuilder()
        var valueKnown = true
        var text = false
        var cdata = false
        var childMarkup = false
    }
    private class Tag(val name: String, val attributes: Int, val close: Boolean, val empty: Boolean, val reliable: Boolean)

    fun describe(xml: String): List<String> {
        val stack = ArrayList<Node>()
        val fields = ArrayList<Field>()
        var authCount = 0; var ticketCount = 0; var nextId = 0; var at = 0; var hierarchyKnown = true
        while (at < xml.length) {
            val current = stack.lastOrNull()?.field
            when {
                xml.startsWith("<![CDATA[", at) -> {
                    val end = xml.indexOf("]]>", at + 9)
                    if (current != null) {
                        current.cdata = true
                        if (end < 0) current.valueKnown = false
                        else if (!appendLiteral(current.value, xml, at + 9, end)) current.valueKnown = false
                    }
                    if (end < 0) { hierarchyKnown = false; break }
                    at = end + 3
                }
                xml.startsWith("<!--", at) -> {
                    val end = xml.indexOf("-->", at + 4)
                    if (end < 0 || xml.substring(at + 4, end).contains("--")) {
                        if (current != null) current.valueKnown = false
                    }
                    if (end < 0) { hierarchyKnown = false; break }
                    at = end + 3
                }
                xml.startsWith("<?", at) -> {
                    if (current != null) current.childMarkup = true
                    val end = xml.indexOf("?>", at + 2)
                    if (end < 0) { hierarchyKnown = false; break }
                    at = end + 2
                }
                xml[at] == '<' -> {
                    val begin = at
                    at = tagEnd(xml, begin)
                    if (at < 0) { hierarchyKnown = false; break }
                    if (xml.startsWith("<!", begin)) {
                        if (current != null) current.childMarkup = true
                        // Never interpret a DTD or custom entity declaration.
                        if (hierarchyKnown) stack.forEach { it.reliable = false }
                        hierarchyKnown = false
                        continue
                    }
                    val tag = tag(xml, begin, at)
                    if (tag.close) {
                        val top = stack.lastOrNull()
                        if (top == null || top.name != tag.name || !tag.reliable) {
                            if (hierarchyKnown) stack.forEach { it.reliable = false }
                            hierarchyKnown = false
                        } else {
                            top.closed = true
                            stack.removeAt(stack.lastIndex)
                        }
                    } else {
                        if (current != null) current.childMarkup = true
                        val node = Node(++nextId, tag.name, tag.attributes, tag.reliable && hierarchyKnown)
                        val kind = when (tag.name) { "auth-url" -> "AUTH_URL"; "ticket-url" -> "TICKET_URL"; else -> null }
                        val ordinal = when (kind) { "AUTH_URL" -> ++authCount; "TICKET_URL" -> ++ticketCount; else -> 0 }
                        if (kind != null && ordinal <= PER_KIND_LIMIT) {
                            val depth = stack.size
                            val field = Field(kind, ordinal, node, stack.take(DEPTH_LIMIT), depth)
                            if (depth > DEPTH_LIMIT) node.reliable = false
                            node.field = field
                            fields += field
                        }
                        if (tag.empty) node.closed = true else stack += node
                    }
                }
                else -> {
                    val end = xml.indexOf('<', at).let { if (it < 0) xml.length else it }
                    if (current != null) {
                        current.text = true
                        if (!appendText(current.value, xml, at, end)) current.valueKnown = false
                    }
                    at = end
                }
            }
        }
        // A completed field whose ancestors are left unclosed has an unreliable parent chain.
        val lines = ArrayList<String>()
        lines += "FIELD_TOTAL kind=AUTH_URL count=$authCount shown=${minOf(authCount, PER_KIND_LIMIT)}"
        lines += "FIELD_TOTAL kind=TICKET_URL count=$ticketCount shown=${minOf(ticketCount, PER_KIND_LIMIT)}"
        val groups = LinkedHashMap<String, Int>()
        for (field in fields) {
            val hierarchy = field.node.reliable && field.node.closed && field.depth <= DEPTH_LIMIT &&
                field.parents.all { it.reliable && it.closed }
            val known = hierarchy && field.valueKnown
            val value = field.value.toString().trim()
            val content = when {
                !known -> "UNKNOWN"
                field.childMarkup -> "CHILD_MARKUP"
                value.isEmpty() -> "EMPTY"
                field.text && field.cdata -> "MIXED"
                field.cdata -> "CDATA"
                else -> "TEXT"
            }
            var path = "UNKNOWN"
            var group = 0
            if (known && !field.childMarkup) {
                if (value.isEmpty()) path = "EMPTY"
                else {
                    val url = try { PortalConfiguration.address(value) } catch (_: Throwable) { null }
                    path = when {
                        url == null -> "INVALID"
                        url.encodedPath.endsWith("/auth.cgi") -> "AUTH_CGI"
                        url.encodedPath.endsWith("/ticket.cgi") -> "TICKET_CGI"
                        else -> "OTHER"
                    }
                    val normalized = url?.toString() ?: value
                    group = groups.getOrPut(normalized) { groups.size + 1 }
                }
            }
            val parents = when {
                !hierarchy -> "UNKNOWN"
                field.parents.isEmpty() -> "ROOT"
                else -> field.parents.joinToString("/") { "T${it.id}" }
            }
            lines += "FIELD kind=${field.kind} ordinal=${field.ordinal} node=T${field.node.id} parentDepth=${field.depth} parents=$parents " +
                "attributes=${field.node.attributes} content=$content endpointPath=$path valueGroup=$group"
        }
        return lines
    }

    private fun tagEnd(xml: String, begin: Int): Int {
        var at = begin + 1; var quote: Char? = null; var brackets = 0
        while (at < xml.length) {
            val c = xml[at++]
            if (quote != null) { if (c == quote) quote = null }
            else when (c) {
                '\'', '"' -> quote = c
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '>' -> if (brackets == 0) return at
            }
        }
        return -1
    }

    /** Attribute names are needed for closure validation and duplicate detection, never rendering. */
    private fun tag(xml: String, begin: Int, end: Int): Tag {
        var at = begin + 1
        val close = xml.getOrNull(at) == '/'
        if (close) at++
        val match = name.matchAt(xml, at)
        if (match == null || match.range.last >= end - 1) return Tag("", 0, close, false, false)
        val elementName = match.value
        at = match.range.last + 1
        var attributes = 0; var reliable = true; var empty = false
        val seen = HashSet<String>()
        while (at < end - 1) {
            val beforeSpace = at
            while (at < end - 1 && xml[at].isWhitespace()) at++
            if (at == end - 1) break
            if (xml[at] == '/' && at == end - 2 && !close) { empty = true; at++; break }
            if (at == beforeSpace || close) { reliable = false; break }
            val attribute = name.matchAt(xml, at)
            if (attribute == null || attribute.range.last >= end - 1) { reliable = false; break }
            if (!seen.add(attribute.value)) reliable = false
            at = attribute.range.last + 1
            while (at < end - 1 && xml[at].isWhitespace()) at++
            if (at >= end - 1 || xml[at++] != '=') { reliable = false; break }
            attributes++
            while (at < end - 1 && xml[at].isWhitespace()) at++
            if (at >= end - 1 || (xml[at] != '\'' && xml[at] != '"')) { reliable = false; break }
            val quote = xml[at++]
            while (at < end - 1 && xml[at] != quote) at++
            if (at >= end - 1) { reliable = false; break }
            at++
        }
        return Tag(elementName, attributes, close, empty, reliable && at == end - 1)
    }

    /** Decode only XML's built-in entities and the exact existing plain-query compatibility. */
    private fun appendText(output: StringBuilder, xml: String, begin: Int, end: Int): Boolean {
        var at = begin; var valid = true
        while (at < end) {
            val c = xml[at]
            if (c == '&') {
                val match = entity.matchAt(xml, at)?.takeIf { it.range.last < end }
                if (match != null) {
                    val token = match.value
                    when (token) {
                        "&amp;" -> output.append('&'); "&lt;" -> output.append('<'); "&gt;" -> output.append('>')
                        "&quot;" -> output.append('"'); "&apos;" -> output.append('\'')
                        else -> {
                            val hex = token.startsWith("&#x")
                            val digits = token.substring(if (hex) 3 else 2, token.length - 1)
                            val value = digits.toIntOrNull(if (hex) 16 else 10)
                            if (value != null && validXmlCharacter(value)) output.appendCodePoint(value) else valid = false
                        }
                    }
                    at = match.range.last + 1
                } else if (query.matchAt(xml, at) != null) { output.append('&'); at++ }
                else { valid = false; at++ }
            } else {
                if (xml.startsWith("]]>", at)) valid = false
                if (Character.isHighSurrogate(c) && at + 1 < end && Character.isLowSurrogate(xml[at + 1])) {
                    output.append(c).append(xml[at + 1]); at += 2
                } else {
                    if (!validXmlCharacter(c.code)) valid = false
                    output.append(c); at++
                }
            }
        }
        return valid
    }

    private fun appendLiteral(output: StringBuilder, xml: String, begin: Int, end: Int): Boolean {
        var at = begin; var valid = true
        while (at < end) {
            val c = xml[at]
            if (Character.isHighSurrogate(c) && at + 1 < end && Character.isLowSurrogate(xml[at + 1])) {
                output.append(c).append(xml[at + 1]); at += 2
            } else {
                if (!validXmlCharacter(c.code)) valid = false
                output.append(c); at++
            }
        }
        return valid
    }

    private fun validXmlCharacter(value: Int): Boolean = value == 9 || value == 10 || value == 13 ||
        value in 0x20..0xD7FF || value in 0xE000..0xFFFD || value in 0x10000..0x10FFFF
}
