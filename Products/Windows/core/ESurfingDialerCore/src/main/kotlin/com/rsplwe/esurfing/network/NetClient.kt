package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.AuthenticationDiagnostics
import com.rsplwe.esurfing.AuthenticationFailure
import com.rsplwe.esurfing.States
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.apache.commons.codec.digest.DigestUtils
import org.apache.log4j.Logger
import java.util.concurrent.TimeUnit

fun createHttpClient(isAllowRedirect: Boolean = true): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(isAllowRedirect)
        .followSslRedirects(isAllowRedirect)
    return builder.build()
}

// Authentication POSTs must not follow a captive redirect with account payloads/headers.
val apiClient = createHttpClient(false)

fun resetApiConnections() {
    apiClient.dispatcher.cancelAll()
    apiClient.connectionPool.evictAll()
}

fun createProbeHttpClient(timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

fun post(url: String, data: String, extraHeaders: HashMap<String, String> = HashMap(),
         responseMetadata: ((Int, String, Long) -> Unit)? = null): NetResult<ResponseBody> {
    val type = "application/x-www-form-urlencoded".toMediaTypeOrNull()
    val body = data.toRequestBody(type)
    val request = Request.Builder()
        .removeHeader("User-Agent")
        .addHeader("User-Agent", Constants.USER_AGENT)
        .addHeader("Accept", Constants.REQUEST_ACCEPT)
        .addHeader("CDC-Checksum", DigestUtils.md5Hex(data))
        .addHeader("Client-ID", States.clientId)
        .addHeader("Algo-ID", States.algoId)
        .url(url)
        .post(body)

    extraHeaders.forEach {
        request.addHeader(it.key, it.value)
    }

    return try {
        val response = apiClient.newCall(request.build()).execute()
        val responseBody = response.body
        responseMetadata?.invoke(response.code, responseBody?.contentType()?.let { "${it.type}/${it.subtype}" } ?: "unknown",
            responseBody?.contentLength() ?: -1)
        if (!response.isSuccessful || responseBody == null) {
            val code = response.code
            response.close()
            NetResult.Error("HTTP $code or empty response body")
        } else {
            NetResult.Success(responseBody)
        }
    } catch (e: Exception) {
        NetResult.Error("transport ${e::class.java.simpleName}")
    }
}

fun requestSessionBootstrap(url: String, algorithm: String): ByteArray {
    val logger = Logger.getLogger("AuthenticationBootstrap")
    when (val result = post(url, algorithm, responseMetadata = { code, type, length ->
        logger.info("TICKET_RESPONSE endpoint=${AuthenticationDiagnostics.endpoint(url)} http=$code contentType=$type declaredLength=$length")
    })) {
        is NetResult.Error -> throw AuthenticationFailure(
            if (result.exception.startsWith("HTTP")) "TICKET_HTTP_ERROR" else "TICKET_TRANSPORT_ERROR")
        is NetResult.Success -> return result.data.use { body ->
            if (body.contentLength() > AuthenticationDiagnostics.MAX_ZSM_BYTES)
                throw AuthenticationFailure("ZSM_TOO_LARGE", true)
            val bytes = try {
                val source = body.source()
                source.request(AuthenticationDiagnostics.MAX_ZSM_BYTES.toLong() + 1)
                source.buffer.readByteArray(minOf(source.buffer.size, AuthenticationDiagnostics.MAX_ZSM_BYTES.toLong() + 1))
            } catch (_: Exception) { throw AuthenticationFailure("TICKET_READ_ERROR") }
            logger.info("TICKET_BODY receivedLength=${bytes.size}")
            if (bytes.size > AuthenticationDiagnostics.MAX_ZSM_BYTES) throw AuthenticationFailure("ZSM_TOO_LARGE", true)
            bytes
        }
    }
}

/** Bound encrypted responses, including chunked bodies; always close the body. */
fun readAuthenticationResponse(body: ResponseBody, stage: String): String = body.use {
    val limit = AuthenticationDiagnostics.MAX_ZSM_BYTES.toLong()
    if (body.contentLength() > limit) throw AuthenticationFailure("${stage}_BODY_TOO_LARGE", true)
    val bytes = try {
        val source = body.source()
        source.request(limit + 1)
        source.buffer.readByteArray(minOf(source.buffer.size, limit + 1))
    } catch (_: Exception) { throw AuthenticationFailure("${stage}_READ_ERROR") }
    if (bytes.size > limit) throw AuthenticationFailure("${stage}_BODY_TOO_LARGE", true)
    if (bytes.isEmpty()) throw AuthenticationFailure("${stage}_BODY_EMPTY", true)
    bytes.toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}
