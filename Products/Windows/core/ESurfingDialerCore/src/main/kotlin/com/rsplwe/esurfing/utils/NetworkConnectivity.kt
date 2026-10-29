package com.rsplwe.esurfing.utils

import cn.yescallop.fluenturi.Uri
import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.AuthenticationFailure
import com.rsplwe.esurfing.RuntimeConfig
import com.rsplwe.esurfing.PortalConfiguration
import com.rsplwe.esurfing.network.createProbeHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

enum class ConnectivityStatus {
    SUCCESS,
    IS_REDIRECTS_NOT_FOUND_IP,
    IS_REDIRECTS_FOUND_IP,
    REQUEST_ERROR,
    DEFAULT
}

data class NetworkConnectivityResult(
    val status: ConnectivityStatus,
    val userIp: String? = "",
    val acIp: String? = "",
    val message: String = "ok",
    val portalUrl: String? = null,
    val portalBody: String? = null,
)

private val probeClient = createProbeHttpClient(RuntimeConfig.networkProbeTimeoutSeconds)

fun checkConnectivity(urls: List<String> = RuntimeConfig.networkCheckUrls, client: OkHttpClient = probeClient): NetworkConnectivityResult {
    val errors = mutableListOf<String>()
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RuntimeConfig.networkProbeBudgetSeconds)

    for (url in urls) {
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remainingMillis <= 0) {
            errors += "network probe budget exhausted"
            break
        }
        try {
            val request = Request.Builder()
                .removeHeader("User-Agent")
                .addHeader("User-Agent", Constants.USER_AGENT)
                .addHeader("Accept", Constants.REQUEST_ACCEPT)
                .url(url)
                .build()

            val call = client.newCall(request)
            call.timeout().timeout(
                minOf(remainingMillis, TimeUnit.SECONDS.toMillis(RuntimeConfig.networkProbeTimeoutSeconds)),
                TimeUnit.MILLISECONDS,
            )
            val (responseCode, location, body) = call.execute().use { response ->
                val bytes = if (response.code == 200 || response.code == 204)
                    response.peekBody(RuntimeConfig.networkProbeBodyLimitBytes + 1).bytes() else byteArrayOf()
                if (bytes.size > RuntimeConfig.networkProbeBodyLimitBytes) throw AuthenticationFailure("PROBE_BODY_TOO_LARGE")
                Triple(response.code, response.headers["Location"], bytes.toString(Charsets.UTF_8))
            }

            when (responseCode) {
                301, 302, 303, 307, 308 -> {
                    return parseRedirect(location?.let { request.url.resolve(it)?.toString() })
                }

                200, 204 -> {
                    val portalResult = parsePortalBody(body, request.url.toString())
                    if (portalResult != null) return portalResult
                    if (isExpectedProbeResponse(request.url, responseCode, body))
                        return NetworkConnectivityResult(status = ConnectivityStatus.SUCCESS)
                    errors += "PROBE_UNEXPECTED_RESPONSE_$responseCode"
                }

                else -> errors += "PROBE_HTTP_$responseCode"
            }
        } catch (e: Exception) {
            errors += if (e is AuthenticationFailure) e.code else "PROBE_TRANSPORT_ERROR"
        }
    }
    return NetworkConnectivityResult(
        ConnectivityStatus.REQUEST_ERROR,
        message = errors.joinToString("; ").ifBlank { "all network checks failed" },
    )
}

private fun parseRedirect(location: String?): NetworkConnectivityResult {
    if (location.isNullOrBlank()) {
        return NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP)
    }
    val params = parseQueryParams(location)
    val userIp = firstParam(params, "wlanuserip", "userIp", "userip", "user_ip", "clientip")
    val acIp = firstParam(params, "wlanacip", "acIp", "acip", "ac_ip", "gwip")
    return if (userIp == null || acIp == null || !PortalConfiguration.isIpv4(userIp) || !PortalConfiguration.isIpv4(acIp)) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, portalUrl = location)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = userIp,
            acIp = acIp,
            portalUrl = location,
        )
    }
}

internal fun isExpectedProbeResponse(url: HttpUrl, code: Int, body: String): Boolean =
    if (url.host == "www.msftconnecttest.com" && url.encodedPath == "/connecttest.txt")
        code == 200 && body == "Microsoft Connect Test"
    else code == 204 && body.isEmpty()

private fun parsePortalBody(body: String, sourceUrl: String): NetworkConnectivityResult? {
    if (body.isBlank()) return null
    try {
        PortalConfiguration.parse(body)?.let {
            return NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_FOUND_IP, it.userIp, it.acIp, portalUrl = sourceUrl, portalBody = body)
        }
    } catch (_: Exception) {
        return NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP,
            message = "PORTAL_CONFIG_INVALID", portalUrl = sourceUrl, portalBody = body)
    }
    val lower = body.lowercase()
    if (!lower.contains("wlanuserip") && !lower.contains("userip") && !lower.contains("wlanacip")) return null

    val userIp = Regex("(?i)(?:wlanuserip|userip|user_ip|clientip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)
    val acIp = Regex("(?i)(?:wlanacip|acip|ac_ip|gwip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)
    val decodedUserIp = userIp?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
    val decodedAcIp = acIp?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
    return if (!PortalConfiguration.isIpv4(decodedUserIp) || !PortalConfiguration.isIpv4(decodedAcIp)) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, portalUrl = sourceUrl, portalBody = body)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = decodedUserIp,
            acIp = decodedAcIp,
            portalUrl = sourceUrl,
            portalBody = body,
        )
    }
}

private fun parseQueryParams(url: String): Map<String, String> {
    return try {
        Uri.from(url).queryParameters().mapKeys { it.key.lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
    } catch (_: Exception) {
        val query = url.substringAfter("?", "")
        query.split("&").mapNotNull {
            val key = it.substringBefore("=", "").takeIf { value -> value.isNotBlank() } ?: return@mapNotNull null
            val value = it.substringAfter("=", "")
            key.lowercase() to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }.toMap()
    }
}

private fun firstParam(params: Map<String, String>, vararg names: String): String? {
    return names.firstNotNullOfOrNull { params[it.lowercase()]?.takeIf { value -> value.isNotBlank() } }
}
