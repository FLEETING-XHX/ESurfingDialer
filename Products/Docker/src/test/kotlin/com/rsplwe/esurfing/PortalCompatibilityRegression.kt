package com.rsplwe.esurfing

/** 合成门户兼容用例；不读取现场页面，不发网络请求，不加载原生认证。 */
object PortalCompatibilityRegression {
    private var checks = 0
    private const val auth = "http://auth.test/auth.cgi"
    private const val ticket = "http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=test"
    private fun page(xml: String) = "<!--//config.campus.js.chinatelecom.com\n$xml\n//config.campus.js.chinatelecom.com-->"
    private fun authField(value: String) = "<auth-url>$value</auth-url>"
    private fun cdata(value: String) = "<![CDATA[$value]]>"
    private fun ticketField(value: String = ticket) = "<ticket-url>${cdata(value)}</ticket-url>"
    private fun verify(label: String, condition: Boolean) { check(condition) { label }; checks++ }
    private fun parses(xml: String): AuthenticationEndpoints = PortalConfiguration.parse(page(xml))
        ?: error("Expected synthetic portal configuration")
    private fun failure(label: String, code: String, xml: String) {
        try { parses(xml); error("$label: expected $code") }
        catch (e: AuthenticationFailure) { verify("$label: expected $code, got ${e.code}", e.code == code) }
    }

    fun run(): Int {
        checks = 0
        combinedFixture()
        normalizedDuplicates()
        invalidDuplicateMembers()
        conflictingAddresses()
        entitySemantics()
        malformedXml()
        return checks
    }

    private fun combinedFixture() {
        // 两个带引号的属性共四个查询分隔符；相同 auth 分别位于根下与更深层。
        val result = parses("""
            <configuration>
              <resources first="http://metadata.test/a?base=0&one=1&two=2"
                         second='http://metadata.test/b?base=0&three=3&four=4'
                         label='synthetic > label'/>
              ${authField(auth)}
              <branches><branch>${authField(cdata(auth))}</branch></branches>
              ${ticketField()}
            </configuration>
        """.trimIndent())
        verify("combined attribute query and nested duplicate auth accepted", result.authUrl == auth)
        verify("combined fixture preserves ticket", result.ticketUrl == ticket)
        verify("combined fixture retains both validated IPs", result.userIp == "10.1.2.3" && result.acIp == "10.1.2.1")
        verify("combined fixture remains portal sourced", result.source == "portal")

        for ((label, markup) in listOf(
            "double quoted attribute" to "<item link=\"http://metadata.test/?first=1&second=2\"/>",
            "single quoted attribute" to "<item link='http://metadata.test/?first=1&second=2'/>",
            "quoted tag closure" to "<item label=\"a > b\" link='http://metadata.test/?first=1&second=2'/>",
            "mixed entity and raw attribute separators" to "<item link=\"http://metadata.test/?first=1&amp;second=2&third=3\"/>",
        )) {
            val parsed = parses(markup + authField(auth) + ticketField())
            verify("$label does not change endpoints", parsed.authUrl == auth && parsed.ticketUrl == ticket)
        }
    }

    private fun normalizedDuplicates() {
        for ((label, first, second) in listOf(
            Triple("host case and default port", "HTTP://AUTH.TEST:80/auth.cgi", auth),
            Triple("dot segments", "http://auth.test/parent/../auth.cgi", auth),
            Triple("outer field whitespace", " \n$auth\t ", cdata(auth)),
            Triple("numeric entities", "http://auth.test/auth.c&#103;&#x69;", cdata(auth)),
        )) {
            val parsed = parses(authField(first) + "<nested>${authField(second)}</nested>" + ticketField())
            verify("normalized duplicate $label accepted", parsed.authUrl == auth)
        }
        val withQuery = "$auth?first=one&second=two"
        val escaped = withQuery.replace("&", "&amp;")
        verify("escaped and CDATA auth queries normalize equally",
            parses(authField(escaped) + authField(cdata(withQuery)) + ticketField()).authUrl == withQuery)
        verify("three equal auth fields can merge after validating every member",
            parses(authField(auth).repeat(3) + ticketField()).authUrl == auth)
        failure("equal auth does not permit duplicate ticket", "PORTAL_TICKET_URL_DUPLICATE",
            authField(auth).repeat(2) + ticketField().repeat(2))
    }

    private fun invalidDuplicateMembers() {
        val invalid = listOf(
            Triple("empty", "", "PORTAL_AUTH_URL_EMPTY"),
            Triple("whitespace", " \n\t ", "PORTAL_AUTH_URL_EMPTY"),
            Triple("nested content", "<nested>$auth</nested>", "PORTAL_AUTH_URL_STRUCTURE_INVALID"),
            Triple("loopback", cdata("http://127.0.0.1/auth.cgi"), "PORTAL_AUTH_URL_INVALID"),
            Triple("file scheme", cdata("file:///auth.cgi"), "PORTAL_AUTH_URL_INVALID"),
            Triple("userinfo", cdata("http://untrusted@auth.test/auth.cgi"), "PORTAL_AUTH_URL_INVALID"),
            Triple("fragment", cdata("$auth#ignored"), "PORTAL_AUTH_URL_INVALID"),
            Triple("relative address", cdata("/relative/auth.cgi"), "PORTAL_AUTH_URL_INVALID"),
            Triple("wrong endpoint path", cdata("http://auth.test/other.cgi"), "PORTAL_ENDPOINT_PATH_INVALID"),
        )
        for ((label, invalidValue, code) in invalid) {
            for (invalidFirst in listOf(true, false)) {
                val bad = authField(invalidValue)
                val good = authField(cdata(auth))
                val fields = if (invalidFirst) bad + good else good + bad
                failure("invalid duplicate $label, invalidFirst=$invalidFirst", code, fields + ticketField())
            }
        }
    }

    private fun conflictingAddresses() {
        for ((label, first, second) in listOf(
            Triple("host", auth, "http://other.test/auth.cgi"),
            Triple("scheme", auth, "https://auth.test/auth.cgi"),
            Triple("port", auth, "http://auth.test:8080/auth.cgi"),
            Triple("path", auth, "http://auth.test/other/auth.cgi"),
            Triple("query presence", auth, "$auth?mode=one"),
            Triple("query value", "$auth?mode=one", "$auth?mode=two"),
            Triple("query ordering", "$auth?first=1&second=2", "$auth?second=2&first=1"),
        )) {
            failure("conflicting duplicate auth $label", "PORTAL_AUTH_URL_DUPLICATE",
                authField(cdata(first)) + "<nested>${authField(cdata(second))}</nested>" + ticketField())
        }
    }

    private fun entitySemantics() {
        val escapedTicket = ticket.replace("&", "&amp;")
        verify("existing amp entities are decoded once",
            parses(authField(auth) + "<ticket-url>$escapedTicket</ticket-url>").ticketUrl == ticket)
        verify("numeric amp entities retain query semantics",
            parses(authField(auth) + "<ticket-url>${ticket.replace("&", "&#38;")}</ticket-url>").ticketUrl == ticket)
        verify("legacy text query compatibility retained",
            parses(authField(auth) + "<ticket-url>$ticket</ticket-url>").ticketUrl == ticket)

        val literalAmp = ticket.replace("portal_node=test", "portal_node=a&amp;b=1")
        verify("CDATA retains literal entity spelling", parses(authField(auth) + ticketField(literalAmp)).ticketUrl == literalAmp)
        val literalUnknown = ticket.replace("portal_node=test", "portal_node=a&unknown;")
        verify("CDATA retains undeclared entity spelling", parses(authField(auth) + ticketField(literalUnknown)).ticketUrl == literalUnknown)
        val onceDecoded = "$auth?first=one&amp;second=two"
        val twiceEscapedXml = "$auth?first=one&amp;amp;second=two"
        verify("duplicate equality compares once decoded values",
            parses(authField(twiceEscapedXml) + authField(cdata(onceDecoded)) + ticketField()).authUrl == onceDecoded)
        verify("comments and processing instructions remain outside compatibility rewriting",
            parses("<!-- synthetic &unknown; raw < text --><?synthetic opaque> query='a=1&b=2' ?>" +
                authField(auth) + ticketField()).authUrl == auth)
    }

    private fun malformedXml() {
        val invalid = listOf(
            "undeclared attribute entity" to "<item value=\"&unknown;\"/>",
            "invalid zero numeric entity" to "<item value='&#0;'/>",
            "out of range numeric entity" to "<item value=\"&#x110000;\"/>",
            "malformed hexadecimal entity" to "<item value=\"&#xQ;\"/>",
            "unterminated named entity" to "<item value=\"&amp\"/>",
            "unterminated numeric entity" to "<item value='&#38'/>",
            "bare non-query ampersand" to "<item value=\"raw&value\"/>",
            "raw attribute less-than" to "<item value=\"raw<value\"/>",
            "duplicate attribute" to "<item value=\"one\" value='two'/>",
            "unquoted attribute" to "<item value=unquoted/>",
            "unclosed query attribute" to "<item value=\"http://metadata.test/?first=1&second=2/>",
            "mismatched tag" to "<item></other>",
            "end tag attribute" to "<item></item value='a=1&b=2'>",
            "DTD internal entity" to "<!DOCTYPE item [<!ENTITY fixture 'a=1&b=2'>]>",
            "DTD external entity" to "<!DOCTYPE item [<!ENTITY fixture SYSTEM 'file:///synthetic-never-read'>]>",
            "undeclared text entity" to "<item>&unknown;</item>",
            "raw text less-than" to "<item>raw<value</item>",
        )
        for ((label, markup) in invalid) {
            failure("strict XML rejection: $label", "PORTAL_CONFIG_XML_INVALID", markup + authField(auth) + ticketField())
        }
    }
}
