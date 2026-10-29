package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.apiClient
import com.rsplwe.esurfing.network.requestSessionBootstrap
import com.rsplwe.esurfing.network.resetApiConnections
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.codec.digest.DigestUtils
import java.net.InetSocketAddress
import java.util.Collections

/** Replay the observed service dispatch: retired android64_vpn is HTTP 200 with an empty body. */
object BootstrapCompatibilityRegression {
    fun run(): Int {
        var count = 0
        fun verify(value: Boolean, name: String) { check(value) { name }; count++; println("PASS: $name") }
        val algorithm = "00000000-0000-0000-0000-000000000000"
        val clientId = "11111111-2222-3333-4444-555555555555"
        val moduleId = "12345678-1234-5678-9abc-123456789abc"
        val module = byteArrayOf(1, 2, 3, 3) + "tag".toByteArray() + byteArrayOf(36) + moduleId.toByteArray() + byteArrayOf(1, 2)
        val requests = Collections.synchronizedList(mutableListOf<List<String>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ticket.cgi") { exchange ->
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val headers = exchange.requestHeaders
            val ua = headers.getFirst("User-Agent").orEmpty()
            requests.add(listOf(exchange.requestMethod, ua, body, headers.getFirst("CDC-Checksum").orEmpty(),
                headers.getFirst("Client-ID").orEmpty(), headers.getFirst("Algo-ID").orEmpty(),
                headers.getFirst("Accept").orEmpty(), headers.getFirst("Content-Type").orEmpty()))
            val supported = ua == "CCTP/android11_64/2104"
            exchange.responseHeaders.add("Content-Type", if (supported) "application/octet-stream" else "text/html")
            exchange.sendResponseHeaders(200, if (supported) module.size.toLong() else -1)
            exchange.responseBody.use { if (supported) it.write(module) }
        }
        val previousClientId = States.clientId
        val previousAlgorithm = States.algoId
        try {
            States.clientId = clientId
            States.algoId = algorithm
            server.start()
            val url = "http://127.0.0.1:${server.address.port}/ticket.cgi"
            apiClient.newCall(Request.Builder().url(url).header("User-Agent", "CCTP/android64_vpn/2085")
                .post(algorithm.toRequestBody()).build()).execute().use {
                verify(it.code == 200 && it.body!!.bytes().isEmpty(), "Retired Windows channel replays HTTP 200 empty bootstrap")
            }
            val received = requestSessionBootstrap(url, algorithm)
            verify(received.contentEquals(module), "Supported ARM64 channel receives module through production bootstrap")
            verify(AuthenticationDiagnostics.inspect(received).algoId == moduleId.uppercase(), "Module reaches metadata inspection")
            val actual = requests.last()
            verify(actual[0] == "POST" && actual[2] == algorithm, "Bootstrap preserves zero UUID POST protocol")
            verify(actual[3] == DigestUtils.md5Hex(algorithm), "Bootstrap checksum matches exact request body")
            verify(actual[4] == clientId && actual[5] == algorithm, "Bootstrap preserves identity and initial Algo-ID headers")
            verify(actual[6] == "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*", "Bootstrap preserves request Accept")
            verify(actual[7].startsWith("application/x-www-form-urlencoded"), "Bootstrap preserves form content type")
        } finally {
            server.stop(0)
            resetApiConnections()
            States.clientId = previousClientId
            States.algoId = previousAlgorithm
        }
        return count
    }
}
