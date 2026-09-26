package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.createProbeHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.OkHttpClient

data class AuthenticationEndpoints(val authUrl: String, val ticketUrl: String, val userIp: String,
                                   val acIp: String, val source: String)

object PortalConfiguration {
    private const val START = "<!--//config.campus.js.chinatelecom.com"
    private const val END = "//config.campus.js.chinatelecom.com-->"
    private const val BODY_LIMIT = 128 * 1024L
    private val client = createProbeHttpClient(10)

    fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit)
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
        val start = body.indexOf(START)
        if (start < 0) return null
        val end = body.indexOf(END, start + START.length)
        if (end < 0 || end - start > BODY_LIMIT) throw AuthenticationFailure("PORTAL_CONFIG_TRUNCATED", true)
        val config = body.substring(start + START.length, end)
        fun tag(name: String): String {
            val values = Regex("<$name>\\s*(?:<!\\[CDATA\\[([\\s\\S]*?)]]>|([^<]*))\\s*</$name>")
                .findAll(config).toList()
            if (values.size != 1) throw AuthenticationFailure("PORTAL_CONFIG_INVALID", true)
            val match = values.single()
            return (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty().replace("&amp;", "&")).trim()
        }
        val auth = address(tag("auth-url"))
        val ticket = address(tag("ticket-url"))
        if (!auth.encodedPath.endsWith("/auth.cgi") || !ticket.encodedPath.endsWith("/ticket.cgi"))
            throw AuthenticationFailure("PORTAL_CONFIG_INVALID", true)
        fun ip(name: String): String = ticket.queryParameterValues(name).singleOrNull()?.takeIf(::isIpv4)
            ?: throw AuthenticationFailure("PORTAL_IP_INVALID", true)
        return AuthenticationEndpoints(auth.toString(), ticket.toString(), ip("wlanuserip"), ip("wlanacip"), "portal")
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
        parse(portalBody.orEmpty())?.let { return it }
        if (!portalUrl.isNullOrBlank()) {
            var url = address(portalUrl)
            repeat(3) {
                val request = Request.Builder().url(url).header("User-Agent", Constants.USER_AGENT).build()
                val next = try {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isRedirect) {
                            val location = response.header("Location") ?: throw AuthenticationFailure("PORTAL_REDIRECT_INVALID")
                            address(url.resolve(location)?.toString().orEmpty())
                        } else {
                            if (!response.isSuccessful) throw AuthenticationFailure("PORTAL_HTTP_ERROR")
                            val bytes = response.peekBody(BODY_LIMIT + 1).bytes()
                            if (bytes.size > BODY_LIMIT) throw AuthenticationFailure("PORTAL_BODY_TOO_LARGE", true)
                            return parse(bytes.toString(Charsets.UTF_8)) ?: legacy(userIp, acIp)
                        }
                    }
                } catch (e: AuthenticationFailure) { throw e }
                catch (_: Exception) { throw AuthenticationFailure("PORTAL_TRANSPORT_ERROR") }
                url = next
            }
            throw AuthenticationFailure("PORTAL_REDIRECT_LIMIT")
        }
        return legacy(userIp, acIp)
    }
}
