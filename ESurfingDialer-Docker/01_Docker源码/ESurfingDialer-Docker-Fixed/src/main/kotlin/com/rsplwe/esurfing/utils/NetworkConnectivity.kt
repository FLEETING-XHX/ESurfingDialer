package com.rsplwe.esurfing.utils

import cn.yescallop.fluenturi.Uri
import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.HealthStatus
import com.rsplwe.esurfing.RuntimeConfig
import com.rsplwe.esurfing.network.createHttpClient
import okhttp3.Request
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
    val message: String = "ok"
)

fun checkConnectivity(): NetworkConnectivityResult {
    val client = createHttpClient(false)
    val errors = mutableListOf<String>()

    for (url in RuntimeConfig.networkCheckUrls) {
        val request = Request.Builder()
            .removeHeader("User-Agent")
            .addHeader("User-Agent", Constants.USER_AGENT)
            .addHeader("Accept", Constants.REQUEST_ACCEPT)
            .url(url)
            .build()

        try {
            val response = client.newCall(request).execute()
            val location = response.headers["Location"]
            val responseCode = response.code
            val body = if (responseCode == 200) response.body?.string().orEmpty() else ""
            response.close()

            when (responseCode) {
                301, 302, 303, 307, 308 -> {
                    return parseRedirect(location)
                }

                200, 204 -> {
                    val portalResult = parsePortalBody(body)
                    if (portalResult != null) return portalResult
                    HealthStatus.markNetworkCheckSuccess()
                    return NetworkConnectivityResult(status = ConnectivityStatus.SUCCESS)
                }

                else -> errors += "$url returned HTTP $responseCode"
            }
        } catch (e: Throwable) {
            errors += "$url: ${e.localizedMessage ?: e::class.java.simpleName}"
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
    return if (userIp.isNullOrBlank() || acIp.isNullOrBlank()) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = userIp,
            acIp = acIp
        )
    }
}

private fun parsePortalBody(body: String): NetworkConnectivityResult? {
    if (body.isBlank()) return null
    val lower = body.lowercase()
    if (!lower.contains("wlanuserip") && !lower.contains("userip") && !lower.contains("wlanacip")) return null

    val userIp = Regex("(?i)(?:wlanuserip|userip|user_ip|clientip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)
    val acIp = Regex("(?i)(?:wlanacip|acip|ac_ip|gwip)=([^&\"'\\s<>]+)")
        .find(body)?.groupValues?.get(1)
    return if (userIp.isNullOrBlank() || acIp.isNullOrBlank()) {
        NetworkConnectivityResult(status = ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP)
    } else {
        NetworkConnectivityResult(
            status = ConnectivityStatus.IS_REDIRECTS_FOUND_IP,
            userIp = URLDecoder.decode(userIp, StandardCharsets.UTF_8),
            acIp = URLDecoder.decode(acIp, StandardCharsets.UTF_8)
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
