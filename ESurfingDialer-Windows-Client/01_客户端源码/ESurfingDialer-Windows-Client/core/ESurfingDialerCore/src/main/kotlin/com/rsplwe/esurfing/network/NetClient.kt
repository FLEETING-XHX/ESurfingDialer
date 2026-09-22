package com.rsplwe.esurfing.network

import com.rsplwe.esurfing.Constants
import com.rsplwe.esurfing.States
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.apache.commons.codec.digest.DigestUtils
import java.util.concurrent.TimeUnit

fun createHttpClient(isAllowRedirect: Boolean = true): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(isAllowRedirect)
        .followSslRedirects(isAllowRedirect)
    return builder.build()
}

val apiClient = createHttpClient()

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
        request.addHeader(it.key, it.value)
    }

    return try {
        val response = apiClient.newCall(request.build()).execute()
        val responseBody = response.body
        if (!response.isSuccessful || responseBody == null) {
            val code = response.code
            response.close()
            NetResult.Error("HTTP $code or empty response body")
        } else {
            NetResult.Success(responseBody)
        }
    } catch (e: Exception) {
        NetResult.Error(e.localizedMessage ?: e::class.java.simpleName)
    }
}
