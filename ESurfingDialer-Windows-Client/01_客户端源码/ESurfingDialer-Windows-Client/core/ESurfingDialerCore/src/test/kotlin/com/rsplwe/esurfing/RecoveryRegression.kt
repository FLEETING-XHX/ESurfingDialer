package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.createProbeHttpClient
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.resetApiConnections
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.checkConnectivity
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

fun main() {
    var count = 0
    fun verify(value: Boolean, name: String) { check(value) { name }; count++; println("PASS: $name") }
    verify(!RecoveryPolicy.shouldReauthenticate(1, 0, 120, 9999), "Single portal probe is debounced")
    verify(!RecoveryPolicy.shouldReauthenticate(99, 0, 1, 9999), "Post-login grace")
    verify(RecoveryPolicy.shouldReauthenticate(12, 0, 120, 9999), "Repeated portal triggers recovery")
    verify(RecoveryPolicy.shouldReauthenticate(1, 1, 120, 9999), "Heartbeat corroborates forced logout")
    verify(!RecoveryPolicy.shouldReauthenticate(99, 2, 120, 5), "Recovery cooldown")
    verify(RuntimeConfig.heartbeatIntervalMaxSeconds == 240L, "Heartbeat interval cap")
    RuntimeConfig.setEnhancedConnection(true)
    verify(RuntimeConfig.networkCheckIntervalSeconds == 5L && RuntimeConfig.healthWriteIntervalSeconds == 15L,
        "Enhanced mode keeps fast network checks with reduced health writes")
    verify(RuntimeConfig.networkCheckDelaySeconds(suspicious = true) == 2L,
        "Enhanced mode accelerates checks only after a suspicious result")
    RuntimeConfig.setEnhancedConnection(false)
    verify(RuntimeConfig.networkCheckIntervalSeconds == 20L && RuntimeConfig.healthWriteIntervalSeconds == 30L,
        "Conservative mode reduces network and disk wakeups")
    verify(RuntimeConfig.networkCheckDelaySeconds(suspicious = true) == 5L,
        "Conservative mode temporarily accelerates suspicious checks")
    RuntimeConfig.setEnhancedConnection(true)
    CoreSignals.wakeClient()
    verify(CoreSignals.waitForClient(10), "Client wake signal is not lost")
    var fakeNow = 1_000L
    val logLimiter = LogRateLimiter(60_000) { fakeNow }
    verify(logLimiter.shouldLog("network") && !logLimiter.shouldLog("network"), "Repeated logs are rate limited")
    fakeNow += 60_000
    verify(logLimiter.shouldLog("network"), "Rate-limited logs recover after the interval")
    val probeClient = createProbeHttpClient(RuntimeConfig.networkProbeTimeoutSeconds)
    verify(probeClient.callTimeoutMillis == 3_000 && probeClient.connectTimeoutMillis == 3_000,
        "Connectivity probes use short bounded timeouts")
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
    val serverExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "regression-http").apply { isDaemon = true }
    }
    server.executor = serverExecutor
    server.createContext("/failure") { exchange -> exchange.sendResponseHeaders(503, -1); exchange.close() }
    server.createContext("/success") { exchange ->
        val data = "confirmed".toByteArray()
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
    }
    server.createContext("/slow") { exchange ->
        try {
            Thread.sleep(5_000)
            exchange.sendResponseHeaders(204, -1)
        } finally {
            exchange.close()
        }
    }
    server.start()
    try {
        val url = "http://127.0.0.1:${server.address.port}"
        verify(post("$url/failure", "test") is NetResult.Error, "HTTP errors cannot authenticate")
        val result = post("$url/success", "test")
        verify(result is NetResult.Success && result.data.use { it.string() } == "confirmed", "Response body handled")
        val probeStartedAt = System.nanoTime()
        val probe = checkConnectivity(listOf("$url/slow"))
        val probeElapsedMillis = (System.nanoTime() - probeStartedAt) / 1_000_000
        verify(probe.status == ConnectivityStatus.REQUEST_ERROR && probeElapsedMillis < 4_500,
            "Slow connectivity probes respect the real call timeout")
    } finally {
        server.stop(0)
        serverExecutor.shutdownNow()
        resetApiConnections()
    }
    println("PASS: $count recovery checks")
    kotlin.system.exitProcess(0)
}
