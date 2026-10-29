package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.AuthenticationFailure
import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.apache.commons.codec.digest.DigestUtils
import java.io.IOException
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

val apiClient = createHttpClient(false)

fun resetApiConnections() {
    apiClient.dispatcher.cancelAll()
    apiClient.connectionPool.evictAll()
}

fun post(url: String, data: String, extraHeaders: HashMap<String, String> = HashMap()): NetResult<ResponseBody> {
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
        request.header(it.key, it.value)
    }

    return try {
        val builtRequest = request.build()
        val response = apiClient.newCall(builtRequest).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            NetResult.Error(IOException("HTTP $code from ${builtRequest.url.host}"))
        } else {
            val responseBody = response.body
            if (responseBody == null) {
                response.close()
                NetResult.Error(IOException("empty response body from ${builtRequest.url.host}"))
            } else {
                NetResult.Success(responseBody)
            }
        }
    } catch (e: Throwable) {
        NetResult.Error(e)
    }
}

fun createProbeHttpClient(timeoutSeconds: Long): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS).callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()

/** Bounded reads also cover chunked responses; always close the body. */
fun readBoundedBody(body: ResponseBody, stage: String, limit: Long): ByteArray = body.use {
    try {
        if (it.contentLength() > limit) throw AuthenticationFailure("${stage}_BODY_TOO_LARGE", true)
        val source = it.source()
        source.request(limit + 1)
        val bytes = source.readByteArray(minOf(source.buffer.size, limit + 1))
        if (bytes.size > limit) throw AuthenticationFailure("${stage}_BODY_TOO_LARGE", true)
        if (bytes.isEmpty()) throw AuthenticationFailure("${stage}_BODY_EMPTY", true)
        bytes
    } catch (e: AuthenticationFailure) { throw e }
    catch (_: Exception) { throw AuthenticationFailure("${stage}_BODY_READ_FAILED") }
}

fun readAuthenticationResponse(body: ResponseBody, stage: String): String =
    readBoundedBody(body, stage, 128 * 1024).toString(Charsets.UTF_8)
