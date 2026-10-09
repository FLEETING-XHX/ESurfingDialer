package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.createProbeHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.OkHttpClient
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.FactoryConfigurationError

data class AuthenticationEndpoints(val authUrl: String, val ticketUrl: String, val userIp: String,
                                   val acIp: String, val source: String)

object PortalConfiguration {
    private const val START = "<!--//config.campus.js.chinatelecom.com"
    private const val END = "//config.campus.js.chinatelecom.com-->"
    private const val BODY_LIMIT = 128 * 1024L
    private val client = createProbeHttpClient(10)

    fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' }
            && (it.toIntOrNull() ?: -1) in 0..255 }
    }

    internal fun address(value: String, errorCode: String = "PORTAL_INVALID_URL"): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw AuthenticationFailure(errorCode, true)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
            || url.host == "localhost" || url.host == "::1" || url.host.startsWith("127.")
            || url.host == "0.0.0.0" || url.host.startsWith("169.254."))
            throw AuthenticationFailure(errorCode, true)
        return url
    }

    fun parse(body: String): AuthenticationEndpoints? {
        var diagnostics = PortalDiagnostics(bodyChars = body.length)
        try {
            if (body.length > BODY_LIMIT) throw AuthenticationFailure("PORTAL_BODY_TOO_LARGE", true)
            val blocks = Regex(Regex.escape(START)).findAll(body).count()
            diagnostics = diagnostics.copy(configBlocks = blocks)
            if (blocks == 0) return null
            if (blocks > 1) throw AuthenticationFailure("PORTAL_CONFIG_DUPLICATE", true)
            val start = body.indexOf(START) + START.length
            val end = body.indexOf(END, start)
            if (end < 0) throw AuthenticationFailure("PORTAL_CONFIG_TRUNCATED", true)
            val config = body.substring(start, end)
            diagnostics = diagnostics.copy(configChars = config.length)
            val root = configXml(config)
            val authFields = root.getElementsByTagName("auth-url")
            val ticketFields = root.getElementsByTagName("ticket-url")
            diagnostics = diagnostics.copy(authFields = authFields.length, ticketFields = ticketFields.length)
            fun field(name: String, code: String): Element {
                val fields = root.getElementsByTagName(name)
                if (fields.length == 0) throw AuthenticationFailure("${code}_MISSING", true)
                if (fields.length > 1) throw AuthenticationFailure("${code}_DUPLICATE", true)
                return fields.item(0) as Element
            }
            if (authFields.length == 0) throw AuthenticationFailure("PORTAL_AUTH_URL_MISSING", true)
            // Validate every occurrence before accepting one unambiguous request URL.
            val authUrls = (0 until authFields.length).map { index ->
                address(fieldText(authFields.item(index) as Element, "PORTAL_AUTH_URL"), "PORTAL_AUTH_URL_INVALID").also {
                    if (!it.encodedPath.endsWith("/auth.cgi")) throw AuthenticationFailure("PORTAL_ENDPOINT_PATH_INVALID", true)
                }
            }.distinctBy { it.toString() }
            if (authUrls.size > 1) throw AuthenticationFailure("PORTAL_AUTH_URL_DUPLICATE", true)
            val ticketField = field("ticket-url", "PORTAL_TICKET_URL")
            val auth = authUrls.single()
            val ticket = address(fieldText(ticketField, "PORTAL_TICKET_URL"), "PORTAL_TICKET_URL_INVALID")
            if (!ticket.encodedPath.endsWith("/ticket.cgi"))
                throw AuthenticationFailure("PORTAL_ENDPOINT_PATH_INVALID", true)
            fun ip(name: String): String = ticket.queryParameterValues(name).singleOrNull()?.takeIf(::isIpv4)
                ?: throw AuthenticationFailure("PORTAL_IP_INVALID", true)
            return AuthenticationEndpoints(auth.toString(), ticket.toString(), ip("wlanuserip"), ip("wlanacip"), "portal")
        } catch (e: AuthenticationFailure) {
            val xml = e.portalDiagnostics
            throw AuthenticationFailure(e.code, e.deterministic, diagnostics.copy(
                xmlStage = xml?.xmlStage ?: PortalXmlStage.NONE,
                xmlLine = xml?.xmlLine ?: -1, xmlColumn = xml?.xmlColumn ?: -1,
                xmlIssue = xml?.xmlIssue ?: PortalXmlIssue.NONE, xmlContext = xml?.xmlContext ?: PortalXmlContext.UNKNOWN))
        }
    }

    private fun fieldText(element: Element, code: String): String {
        val value = StringBuilder()
        for (i in 0 until element.childNodes.length) {
            val node = element.childNodes.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> value.append(node.nodeValue)
                Node.COMMENT_NODE -> Unit
                else -> throw AuthenticationFailure("${code}_STRUCTURE_INVALID", true)
            }
        }
        return value.toString().trim().takeIf { it.isNotEmpty() }
            ?: throw AuthenticationFailure("${code}_EMPTY", true)
    }

    private fun configXml(config: String): Element {
        fun setupFailure() = AuthenticationFailure("PORTAL_XML_PARSER_SETUP_FAILED", true,
            PortalDiagnostics(xmlStage = PortalXmlStage.PARSER_SETUP))
        val builder = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().apply { setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            }) }
        } catch (_: Exception) { throw setupFailure() }
        catch (_: FactoryConfigurationError) { throw setupFailure() }
        // A portal comment can contain an XML fragment or one declared document.
        val fragment = config.trim().removePrefix("\uFEFF").trimStart()
            .replaceFirst(Regex("^<\\?xml\\s[^?]*\\?>"), "")
        val xml = "<portal-config>${escapeQuerySeparators(fragment)}</portal-config>"
        try {
            return builder.parse(InputSource(StringReader(xml))).documentElement
        } catch (e: SAXParseException) {
            // Positions are safe; parser messages can contain URLs, attributes or field values.
            throw AuthenticationFailure("PORTAL_CONFIG_XML_INVALID", true,
                PortalDiagnostics(xmlStage = PortalXmlStage.XML_PARSE, xmlLine = e.lineNumber, xmlColumn = e.columnNumber,
                    xmlIssue = PortalXmlIssue.fromMessage(e.message), xmlContext = PortalXmlDetails.context(xml, e.lineNumber, e.columnNumber)))
        } catch (e: SAXException) {
            throw AuthenticationFailure("PORTAL_CONFIG_XML_INVALID", true,
                PortalDiagnostics(xmlStage = PortalXmlStage.XML_PARSE, xmlIssue = PortalXmlIssue.fromMessage(e.message)))
        } catch (_: Exception) {
            throw AuthenticationFailure("PORTAL_XML_PARSER_FAILED", true,
                PortalDiagnostics(xmlStage = PortalXmlStage.XML_PARSE))
        }
    }

    /** Preserve legacy &key=value in text and quoted attributes; entities and opaque markup stay intact. */
    private fun escapeQuerySeparators(xml: String): String = buildString {
        var at = 0
        val separator = Regex("&(?=[A-Za-z_][A-Za-z0-9_.~-]*=)")
        while (at < xml.length) {
            val end = when {
                xml.startsWith("<![CDATA[", at) -> xml.indexOf("]]>", at + 9).let { if (it < 0) xml.length else it + 3 }
                xml.startsWith("<!--", at) -> xml.indexOf("-->", at + 4).let { if (it < 0) xml.length else it + 3 }
                xml.startsWith("<?", at) -> xml.indexOf("?>", at + 2).let { if (it < 0) xml.length else it + 2 }
                xml[at] == '<' -> {
                    var cursor = at + 1
                    var quote: Char? = null
                    while (cursor < xml.length) {
                        val c = xml[cursor++]
                        if (quote != null) { if (c == quote) quote = null }
                        else if (c == '\'' || c == '"') quote = c
                        else if (c == '>') break
                    }
                    cursor
                }
                else -> xml.indexOf('<', at).let { if (it < 0) xml.length else it }
            }
            val segment = xml.substring(at, end)
            append(when {
                xml[at] != '<' -> separator.replace(segment, "&amp;")
                segment.startsWith("<!") || segment.startsWith("<?") || segment.startsWith("</") -> segment
                else -> escapeAttributeQuerySeparators(segment, separator)
            })
            at = end
        }
    }

    private fun escapeAttributeQuerySeparators(markup: String, separator: Regex): String = buildString {
        var at = 0
        while (at < markup.length) {
            var quoteAt = at
            while (quoteAt < markup.length && markup[quoteAt] != '\'' && markup[quoteAt] != '"') quoteAt++
            if (quoteAt == markup.length) { append(markup, at, markup.length); break }
            val end = markup.indexOf(markup[quoteAt], quoteAt + 1)
            // Do not repair an unterminated attribute; the XML parser must reject it.
            if (end < 0) { append(markup, at, markup.length); break }
            append(markup, at, quoteAt + 1)
            append(separator.replace(markup.substring(quoteAt + 1, end), "&amp;"))
            append(markup[end])
            at = end + 1
        }
    }

    fun legacy(userIp: String, acIp: String): AuthenticationEndpoints {
        if (!isIpv4(userIp) || !isIpv4(acIp)) throw AuthenticationFailure("PORTAL_IP_INVALID", true)
        val ticket = Constants.BASE_URL.toHttpUrlOrNull()!!.newBuilder().addPathSegment("ticket.cgi")
            .addQueryParameter("wlanuserip", userIp).addQueryParameter("wlanacip", acIp)
            .addQueryParameter("portal_node", Constants.PORTAL_NODE).build()
        return AuthenticationEndpoints(Constants.AUTH_URL, ticket.toString(), userIp, acIp, "legacy_fallback")
    }

    fun discover(portalUrl: String?, portalBody: String?, userIp: String, acIp: String,
                 httpClient: OkHttpClient = client): AuthenticationEndpoints {
        val cachedFailure = try {
            parse(portalBody.orEmpty())?.let { return it }
            null
        } catch (e: AuthenticationFailure) { e }
        if (!portalUrl.isNullOrBlank()) {
            var url = address(portalUrl)
            repeat(3) { hop ->
                val request = Request.Builder().url(url).header("User-Agent", Constants.USER_AGENT).build()
                var metadata = PortalDiagnostics(hop = hop + 1)
                val next = try {
                    httpClient.newCall(request).execute().use { response ->
                        metadata = metadata.copy(httpStatus = response.code,
                            mediaType = PortalMediaType.fromHeader(response.header("Content-Type")))
                        if (response.isRedirect) {
                            val location = response.header("Location") ?: throw AuthenticationFailure("PORTAL_REDIRECT_INVALID")
                            address(url.resolve(location)?.toString().orEmpty())
                        } else {
                            if (!response.isSuccessful) throw AuthenticationFailure("PORTAL_HTTP_ERROR")
                            val bytes = response.peekBody(BODY_LIMIT + 1).bytes()
                            metadata = metadata.copy(bodyBytes = bytes.size)
                            if (bytes.size > BODY_LIMIT) throw AuthenticationFailure("PORTAL_BODY_TOO_LARGE", true)
                            parse(bytes.toString(Charsets.UTF_8))?.let { return it }
                            // A bad cached config may recover through a fresh valid config, not a silent fallback.
                            if (cachedFailure != null) throw AuthenticationFailure(cachedFailure.code,
                                cachedFailure.deterministic, cachedFailure.portalDiagnostics?.copy(cachedConfig = true))
                            val userIps = url.queryParameterValues("wlanuserip")
                            val acIps = url.queryParameterValues("wlanacip")
                            if (userIps.size > 1 || acIps.size > 1) throw AuthenticationFailure("PORTAL_IP_INVALID", true)
                            val discoveredUserIp = userIps.singleOrNull()
                            val discoveredAcIp = acIps.singleOrNull()
                            return if (discoveredUserIp != null || discoveredAcIp != null)
                                legacy(discoveredUserIp.orEmpty(), discoveredAcIp.orEmpty())
                            else legacy(userIp, acIp)
                        }
                    }
                } catch (e: AuthenticationFailure) {
                    val details = (e.portalDiagnostics ?: PortalDiagnostics()).copy(bodyBytes = metadata.bodyBytes,
                        httpStatus = metadata.httpStatus, hop = metadata.hop, mediaType = metadata.mediaType)
                    throw AuthenticationFailure(e.code, e.deterministic, details)
                } catch (_: Exception) { throw AuthenticationFailure("PORTAL_TRANSPORT_ERROR", false, metadata) }
                url = next
            }
            throw AuthenticationFailure("PORTAL_REDIRECT_LIMIT")
        }
        if (cachedFailure != null) throw cachedFailure
        return legacy(userIp, acIp)
    }
}
