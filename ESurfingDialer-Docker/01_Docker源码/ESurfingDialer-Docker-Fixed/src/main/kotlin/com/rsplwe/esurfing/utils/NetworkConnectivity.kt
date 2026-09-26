package com.rsplwe.esurfing.utils

import cn.yescallop.fluenturi.Uri
import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.HealthStatus
import com.rsplwe.esurfing.RuntimeConfig
import com.rsplwe.esurfing.AuthenticationFailure
import com.rsplwe.esurfing.PortalConfiguration
import com.rsplwe.esurfing.network.createProbeHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
    val portalBody: String? = null
)

private val probeClient = createProbeHttpClient(10)

fun checkConnectivity(urls: List<String> = RuntimeConfig.networkCheckUrls, client: OkHttpClient = probeClient): NetworkConnectivityResult {
    val errors = mutableListOf<String>()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RuntimeConfig.networkProbeBudgetSeconds)

    for (url in urls.take(8)) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) { errors += "PROBE_BUDGET_EXCEEDED"; break }
        val request = Request.Builder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", Constants.USER_AGENT)
            .addHeader("Accept", Constants.REQUEST_ACCEPT)
            .url(url)
            .build()

        try {
            val call = client.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                when (response.code) {
                    301, 302, 303, 307, 308 -> {
                        val location = response.header("Location")
                        return parseRedirect(location?.let { response.request.url.resolve(it)?.toString() })
                    }
                    200, 204 -> {
                        val bytes = response.peekBody(128 * 1024L + 1).bytes()
                        if (bytes.size > 128 * 1024) throw AuthenticationFailure("PROBE_BODY_TOO_LARGE")
                        val portalResult = parsePortalBody(bytes.toString(Charsets.UTF_8), response.request.url.toString())
                        if (portalResult != null) return portalResult
                        HealthStatus.markNetworkCheckSuccess()
                        return NetworkConnectivityResult(status = ConnectivityStatus.SUCCESS)
                    }
                    else -> errors += "PROBE_HTTP_${response.code}"
                }
            }
        } catch (e: Exception) {
            errors += if (e is AuthenticationFailure) e.code else "PROBE_TRANSPORT_ERROR"
        }
    }
    val message = errors.joinToString("; ").ifBlank { "all network checks failed" }
    return NetworkConnectivityResult(ConnectivityStatus.REQUEST_ERROR, message = message)
}

private fun parseRedirect(location: String?): NetworkConnectivityResult {
    if (location.isNullOrBlank()) {
        return NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP)
    }
    val params = parseQueryParams(location)
    val userIp = firstParam(params, "wlanuserip", "userIp", "userip", "user_ip", "clientip")
    val acIp = firstParam(params, "wlanacip", "acIp", "acip", "ac_ip", "gwip")
    return if (!PortalConfiguration.isIpv4(userIp.orEmpty()) || !PortalConfiguration.isIpv4(acIp.orEmpty())) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, portalUrl = location)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = userIp,
            acIp = acIp,
            portalUrl = location
        )
    }
}

private fun parsePortalBody(body: String, url: String): NetworkConnectivityResult? {
    try {
        PortalConfiguration.parse(body)?.let {
            return NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_FOUND_IP, it.userIp, it.acIp, portalUrl = url, portalBody = body)
        }
    } catch (e: AuthenticationFailure) {
        return NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, message = e.code, portalUrl = url, portalBody = body)
    }
    if (body.isBlank()) return null
    val lower = body.lowercase()
    if (!lower.contains("wlanuserip") && !lower.contains("userip") && !lower.contains("wlanacip")) return null

    val userIp = Regex("(?i)(?:wlanuserip|userip|user_ip|clientip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    val acIp = Regex("(?i)(?:wlanacip|acip|ac_ip|gwip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    return if (!PortalConfiguration.isIpv4(userIp.orEmpty()) || !PortalConfiguration.isIpv4(acIp.orEmpty())) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, portalUrl = url, portalBody = body)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = userIp,
            acIp = acIp,
            portalUrl = url, portalBody = body
        )
    }
}

private fun parseQueryParams(url: String): Map<String, String> {
    return try {
        Uri.from(url).queryParameters().mapKeys { it.key.lowercase() }
            .mapValues { it.value.firstOrNull().orEmpty() }
    } catch (_: Throwable) {
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
