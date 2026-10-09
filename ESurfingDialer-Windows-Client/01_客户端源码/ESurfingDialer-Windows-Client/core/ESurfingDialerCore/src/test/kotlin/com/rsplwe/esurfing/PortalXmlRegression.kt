package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.createProbeHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** 合成 XML 与 OkHttp 拦截响应；不发真实网络请求，不调用任何原生认证。 */
object PortalXmlRegression {
    private var checks = 0
    private const val secret = "fixture-sensitive-never-print"
    private const val auth = "http://auth.test/auth.cgi"
    private const val ticket = "http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=fixture-sensitive-never-print"
    private val fields = "<auth-url><![CDATA[$auth]]></auth-url><ticket-url><![CDATA[$ticket]]></ticket-url>"
    private const val providerProperty = "javax.xml.parsers.DocumentBuilderFactory"
    private fun page(xml: String) = "<!--//config.campus.js.chinatelecom.com\n$xml\n//config.campus.js.chinatelecom.com-->"
    private fun verify(label: String, condition: Boolean) { check(condition) { label }; checks++ }
    private fun failure(code: String, action: () -> Unit): AuthenticationFailure {
        try { action(); error("Expected $code") }
        catch (e: AuthenticationFailure) { verify("Expected $code, got ${e.code}", e.code == code); return e }
    }
    private fun privateSummary(failure: AuthenticationFailure): Boolean {
        val text = "${failure.message} ${failure.portalDiagnostics?.summary()} ${failure.portalDiagnostics}"
        return failure.cause == null && failure.suppressed.isEmpty() && failure.message == failure.code &&
            listOf(secret, "http://", "10.1.2.", "private-tag", "private-attribute", "private-entity", "StackTrace", "SAXParseException")
                .none(text::contains)
    }
    private fun withUnavailableParser(action: () -> Unit) {
        val original = System.getProperty(providerProperty)
        try {
            System.setProperty(providerProperty, "$secret.parser.not.found")
            action()
        } finally {
            if (original == null) System.clearProperty(providerProperty) else System.setProperty(providerProperty, original)
        }
    }

    fun run(): Int {
        checks = 0
        parserSetup()
        syntaxAndPrivacy()
        localizedCategories()
        contextBounds()
        discoveryMetadata()
        return checks
    }

    private fun parserSetup() {
        withUnavailableParser {
            val error = failure("PORTAL_XML_PARSER_SETUP_FAILED") { PortalConfiguration.parse(page(fields)) }
            verify("parser setup is distinct from XML content", error.portalDiagnostics?.let {
                it.xmlStage == PortalXmlStage.PARSER_SETUP && it.xmlLine == -1 && it.xmlColumn == -1 && it.configChars > 0
            } == true)
            verify("setup failure does not claim parsed fields or XML syntax", error.portalDiagnostics?.let {
                it.authFields == -1 && it.ticketFields == -1 && it.xmlIssue == PortalXmlIssue.NONE && it.xmlContext == PortalXmlContext.UNKNOWN
            } == true)
            verify("provider exception and provider class stay private", privateSummary(error))
        }
        verify("factory property restored after failure", PortalConfiguration.parse(page(fields))?.authUrl == auth)
    }

    private fun syntaxAndPrivacy() {
        val stderr = ByteArrayOutputStream()
        val originalError = System.err
        val capturedError = PrintStream(stderr)
        try {
            System.setErr(capturedError)
            val malformedBody = page("<private-tag private-attribute='$secret'>$fields")
            val malformed = failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(malformedBody) }
            verify("XML_PARSE includes normalized input position and lengths", malformed.portalDiagnostics?.let {
                it.xmlStage == PortalXmlStage.XML_PARSE && it.xmlLine > 0 && it.xmlColumn > 0 &&
                    it.configChars > 0 && it.bodyChars == malformedBody.length
            } == true)
            verify("fields remain unknown when DOM parsing fails", malformed.portalDiagnostics?.let {
                it.configBlocks == 1 && it.authFields == -1 && it.ticketFields == -1
            } == true)
            verify("parse failure retains fixed code rather than message or cause", privateSummary(malformed))

            for (newline in listOf("\n", "\r\n")) {
                val entity = failure("PORTAL_CONFIG_XML_INVALID") {
                    PortalConfiguration.parse(page("<root>$newline<item private-attribute=\"http://private.test/?base=1&private-entity\"/>$newline</root>$fields"))
                }
                verify("attribute entity error uses safe Issue and Context with both line endings", entity.portalDiagnostics?.let {
                    it.xmlStage == PortalXmlStage.XML_PARSE && it.xmlIssue == PortalXmlIssue.ENTITY_REFERENCE &&
                        it.xmlContext == PortalXmlContext.ATTRIBUTE && it.xmlLine > 1 && it.xmlColumn > 0
                } == true)
                verify("attribute diagnostics retain no raw attribute or URL", privateSummary(entity))
            }

            for ((markup, issue) in listOf(
                "<item private-attribute=\"one\" private-attribute=\"two\"/>" to PortalXmlIssue.DUPLICATE_ATTRIBUTE,
                "<item private-attribute=\"raw<value\"/>" to PortalXmlIssue.ATTRIBUTE_LT,
                "<item private-attribute=unquoted/>" to PortalXmlIssue.ATTRIBUTE_QUOTE,
                "<item></other>" to PortalXmlIssue.TAG_MISMATCH,
                "<item>&private-entity;</item>" to PortalXmlIssue.UNDECLARED_ENTITY,
            )) {
                val error = failure("PORTAL_CONFIG_XML_INVALID") { PortalConfiguration.parse(page(markup + fields)) }
                verify("known parser syntax maps to its fixed category", error.portalDiagnostics?.xmlIssue == issue)
                verify("known parser syntax never includes names, values or chained exceptions", privateSummary(error))
            }
            val dtd = failure("PORTAL_CONFIG_XML_INVALID") {
                PortalConfiguration.parse(page("<!DOCTYPE root [<!ENTITY private-entity SYSTEM 'file:///$secret'>]>$fields"))
            }
            verify("DTD safety rejection remains XML content failure", dtd.portalDiagnostics?.xmlStage == PortalXmlStage.XML_PARSE && privateSummary(dtd))
            val deep = failure("PORTAL_CONFIG_XML_INVALID") {
                PortalConfiguration.parse(page("<item>".repeat(70) + fields + "</item>".repeat(70)))
            }
            verify("depth limit retains safe category", deep.portalDiagnostics?.xmlIssue == PortalXmlIssue.DEPTH_LIMIT && privateSummary(deep))
        } finally {
            System.setErr(originalError)
            capturedError.close()
        }
        verify("parser emits no raw message or stack to stderr", stderr.size() == 0)
    }

    private fun localizedCategories() {
        // 固定合成模板同时覆盖中英文；不改变进程默认语言，不依赖现场异常原文。
        for ((message, issue) in listOf(
            "The reference to entity \"$secret\" must end with the ';' delimiter." to PortalXmlIssue.ENTITY_REFERENCE,
            "对实体 \"$secret\" 的引用必须以 ';' 分隔符结尾。" to PortalXmlIssue.ENTITY_REFERENCE,
            "The entity \"$secret\" was referenced, but not declared." to PortalXmlIssue.UNDECLARED_ENTITY,
            "引用了实体 \"$secret\", 但未声明它。" to PortalXmlIssue.UNDECLARED_ENTITY,
            "Attribute \"$secret\" was already specified for element \"private-tag\"." to PortalXmlIssue.DUPLICATE_ATTRIBUTE,
            "已经为元素 \"private-tag\" 指定属性 \"$secret\"。" to PortalXmlIssue.DUPLICATE_ATTRIBUTE,
            "The value of attribute \"$secret\" associated with an element type \"private-tag\" must not contain the '<' character." to PortalXmlIssue.ATTRIBUTE_LT,
            "与元素类型 \"private-tag\" 相关联的 \"$secret\" 属性值不能包含 '<' 字符。" to PortalXmlIssue.ATTRIBUTE_LT,
            "Open quote is expected for attribute \"$secret\" associated with an element type \"private-tag\"." to PortalXmlIssue.ATTRIBUTE_QUOTE,
            "与元素类型 \"private-tag\" 相关联的属性 \"$secret\" 应有左引号。" to PortalXmlIssue.ATTRIBUTE_QUOTE,
            "The element type \"private-tag\" must be terminated by the matching end-tag \"</private-tag>\"." to PortalXmlIssue.TAG_MISMATCH,
            "元素类型 \"private-tag\" 必须由匹配的结束标记 \"</private-tag>\" 终止。" to PortalXmlIssue.TAG_MISMATCH,
            "JAXP00010006: The element has a depth of 65 that exceeds the limit of 64." to PortalXmlIssue.DEPTH_LIMIT,
            "JAXP00010006: 元素的深度为 65, 超出了限制 64。" to PortalXmlIssue.DEPTH_LIMIT,
        )) verify("English and Chinese parser templates map to fixed categories", PortalXmlIssue.fromMessage(message) == issue)
        verify("unknown parser template stays a fixed fallback", PortalXmlIssue.fromMessage("$secret arbitrary parser message") == PortalXmlIssue.OTHER_XML_SYNTAX)
        verify("absent parser message stays a fixed fallback", PortalXmlIssue.fromMessage(null) == PortalXmlIssue.OTHER_XML_SYNTAX)
    }

    private fun contextBounds() {
        val attribute = "<root private-attribute='$secret'/>"
        verify("known position points inside quoted attribute", PortalXmlDetails.context(attribute, 1, attribute.indexOf(secret) + 3) == PortalXmlContext.ATTRIBUTE)
        val text = "<root>$secret</root>"
        verify("known position points inside text", PortalXmlDetails.context(text, 1, text.indexOf(secret) + 3) == PortalXmlContext.TEXT)
        verify("unknown parser position has UNKNOWN context", PortalXmlDetails.context(attribute, -1, -1) == PortalXmlContext.UNKNOWN)
        verify("out of range row has UNKNOWN context", PortalXmlDetails.context(attribute, 100, 1) == PortalXmlContext.UNKNOWN)
        verify("out of range column has UNKNOWN context", PortalXmlDetails.context(attribute, 1, 10000) == PortalXmlContext.UNKNOWN)
    }

    private fun discoveryMetadata() {
        val malformed = page("<root><item private-attribute=\"http://private.test/?base=1&private-entity\"/></root>$fields")
        val requests = mutableListOf<okhttp3.Request>()
        // 拦截器直接返回合成 HTTP 响应，避免任何 DNS 或 socket 请求。
        val client = createProbeHttpClient(2).newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val redirect = request.url.encodedPath == "/redirect"
            val body = when (request.url.encodedPath) { "/ordinary" -> "ordinary page"; "/redirect" -> ""; else -> malformed }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (redirect) 302 else 200).message(if (redirect) "Found" else "OK")
                .header("Content-Type", "text/html; private=$secret")
                .apply { if (redirect) header("Location", "/malformed?private=$secret") }
                .body(body.toResponseBody()).build()
        }.build()
        try {
            val live = failure("PORTAL_CONFIG_XML_INVALID") {
                PortalConfiguration.discover("http://portal.test/redirect", null, "", "", client)
            }
            verify("HTTP and XML metadata both survive fresh discovery", live.portalDiagnostics?.let {
                it.httpStatus == 200 && it.hop == 2 && it.mediaType == PortalMediaType.HTML && !it.cachedConfig &&
                    it.bodyBytes == malformed.toByteArray().size && it.configChars > 0 && it.configBlocks == 1 &&
                    it.xmlStage == PortalXmlStage.XML_PARSE && it.xmlLine > 0 && it.xmlColumn > 0 &&
                    it.xmlIssue == PortalXmlIssue.ENTITY_REFERENCE && it.xmlContext == PortalXmlContext.ATTRIBUTE
            } == true)
            verify("redirect chain remains bounded GET discovery with unchanged UA", requests.size == 2 &&
                requests.all { it.method == "GET" && it.header("User-Agent") == Constants.USER_AGENT })
            verify("HTTP URLs and header parameters are absent from XML summary", privateSummary(live))

            val cached = failure("PORTAL_CONFIG_XML_INVALID") {
                PortalConfiguration.discover("http://portal.test/ordinary", malformed, "10.1.2.3", "10.1.2.1", client)
            }
            verify("cached XML failure retains XML details and current HTTP metadata", cached.portalDiagnostics?.let {
                it.cachedConfig && it.bodyChars == malformed.length && it.bodyBytes == "ordinary page".toByteArray().size &&
                    it.httpStatus == 200 && it.hop == 1 && it.mediaType == PortalMediaType.HTML && it.configChars > 0 &&
                    it.xmlStage == PortalXmlStage.XML_PARSE && it.xmlIssue == PortalXmlIssue.ENTITY_REFERENCE &&
                    it.xmlContext == PortalXmlContext.ATTRIBUTE && it.xmlLine > 0 && it.xmlColumn > 0
            } == true)
            verify("cached failure still carries no raw content or parser cause", privateSummary(cached))

            withUnavailableParser {
                val setup = failure("PORTAL_XML_PARSER_SETUP_FAILED") {
                    PortalConfiguration.discover("http://portal.test/malformed", null, "", "", client)
                }
                verify("live parser setup retains HTTP metadata without claiming XML position", setup.portalDiagnostics?.let {
                    it.xmlStage == PortalXmlStage.PARSER_SETUP && it.httpStatus == 200 && it.hop == 1 &&
                        it.mediaType == PortalMediaType.HTML && it.bodyBytes > 0 && it.configChars > 0 &&
                        it.xmlLine == -1 && it.xmlColumn == -1 && it.authFields == -1 && it.ticketFields == -1
                } == true)
                verify("live factory failure does not render private provider or response details", privateSummary(setup))
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
