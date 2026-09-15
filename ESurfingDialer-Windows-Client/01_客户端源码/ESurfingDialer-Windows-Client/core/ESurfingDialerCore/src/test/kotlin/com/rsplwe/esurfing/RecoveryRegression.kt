package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.resetApiConnections
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress

fun main() {
    var count = 0
    fun verify(value: Boolean, name: String) { check(value) { name }; count++; println("PASS: $name") }
    verify(!RecoveryPolicy.shouldReauthenticate(1, 0, 120, 9999), "Single portal probe is debounced")
    verify(!RecoveryPolicy.shouldReauthenticate(99, 0, 1, 9999), "Post-login grace")
    verify(RecoveryPolicy.shouldReauthenticate(12, 0, 120, 9999), "Repeated portal triggers recovery")
    verify(RecoveryPolicy.shouldReauthenticate(1, 1, 120, 9999), "Heartbeat corroborates forced logout")
    verify(!RecoveryPolicy.shouldReauthenticate(99, 2, 120, 5), "Recovery cooldown")
    verify(RuntimeConfig.heartbeatIntervalMaxSeconds == 240L, "Heartbeat interval cap")
    States.rootDir.mkdirs()
    HealthStatus.markError("first\nsecond\t\"quoted\"\\slash")
    HealthStatus.write()
    val health = File(States.rootDir, "health.json").readText()
    verify(health.contains("\\u000a") && health.contains("\\u0009"), "Health JSON escapes control characters")
    verify(!File(States.rootDir, "health.json.tmp").exists(), "Health snapshot atomically replaced")
    HealthStatus.consecutiveHeartbeatFailures.set(3)
    HealthStatus.markHeartbeatSuccess()
    verify(HealthStatus.consecutiveHeartbeatFailures.get() == 0, "Successful heartbeat resets failures")
    HealthStatus.consecutivePortalDetections.set(8)
    HealthStatus.markNetworkCheckSuccess()
    verify(HealthStatus.consecutivePortalDetections.get() == 0, "Successful probe resets portal count")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/failure") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
    server.createContext("/success") { exchange ->
        val data = "confirmed".toByteArray()
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
    }
    server.start()
    try {
        val url = "http://127.0.0.1:${server.address.port}"
        verify(post("$url/failure", "test") is NetResult.Error, "HTTP errors cannot authenticate")
        val result = post("$url/success", "test")
        verify(result is NetResult.Success && result.data.use { it.string() } == "confirmed", "Response body handled")
    } finally { server.stop(0); resetApiConnections() }
    println("PASS: $count recovery checks")
    kotlin.system.exitProcess(0)
}
