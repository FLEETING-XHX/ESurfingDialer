package com.rsplwe.esurfing

import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object OperationsRegression {
    private var checks = 0
    private val root = Files.createTempDirectory(States.rootDir.toPath().also { Files.createDirectories(it) }, "operations-").toFile()
    private var wall = OffsetDateTime.parse("2026-09-26T04:00:00+08:00").toEpochSecond()
    private var nanos = 10_000_000_000L
    private val base = wall
    private fun verify(label: String, value: Boolean) { check(value) { label }; checks++ }
    private fun rejected(label: String, operation: () -> Unit) {
        try { operation(); error("accepted invalid $label") } catch (_: IllegalArgumentException) { checks++ }
    }
    private fun engine(name: String, start: Int = 4, end: Int = 6, enabled: Boolean = true,
        writer: (File, String) -> Unit = StateFiles::write) = ReauthPlannerEngine(File(root, "$name.json"), enabled, start, end, { wall }, { nanos }, writer)
    private fun advance(seconds: Long, monotonicSeconds: Long = seconds) { wall += seconds; nanos += monotonicSeconds * 1_000_000_000L }
    private fun seed(name: String, schema: Int = 2, estimate: Long = 3600, auth: Long = wall,
        next: Long = wall + 2700, updated: Long = wall, observed: Long = 0, samples: Int = 0) {
        StateFiles.write(File(root, "$name.json"), StateJson.encode(linkedMapOf("schemaVersion" to schema,
            "estimatedLifetimeSeconds" to estimate, "lastAuthSuccessAt" to auth, "nextPlannedReauthAt" to next,
            "lastObservedLifetimeSeconds" to observed, "successfulAuthSamples" to samples, "updatedAt" to updated)))
    }

    @JvmStatic fun main(args: Array<String>) {
        if (args.firstOrNull() == "--stall-child") {
            HealthStatus.clientThreadAlive = true
            HealthStatus.markClientProgress("fixture_stall", 5)
            Runtime.getRuntime().addShutdownHook(Thread { File(States.rootDir, "unexpected-hook").writeText("ran") })
            HealthStatus.startReporter()
            Thread.sleep(15000)
            error("supervisor failed to exit")
        }
        jsonAndFiles()
        samples()
        scheduleAndState()
        verify("future auth is not fresh", HealthStatus.ageSeconds(1000, 1300) == Long.MAX_VALUE)
        verify("valid past auth age", HealthStatus.ageSeconds(1000, 900) == 100L)
        HealthStatus.markClientProgress("fixture", 5)
        verify("client stage alive before deadline", !HealthStatus.clientStageExpired())
        verify("client stage uses monotonic deadline", HealthStatus.clientStageExpired(System.nanoTime() + 6_000_000_000L))
        HealthStatus.markError("quote\" slash\\ newline\n tab\t control" + 1.toChar())
        HealthStatus.write()
        val health = StateJson.decode(File(States.rootDir, "health.json").readText())
        verify("health error JSON escaped", health["lastError"] == HealthStatus.lastError)
        verify("schema and progress published", health["schemaVersion"] == 2L && health["clientStageDeadlineAt"] is Long)
        longRetryProgress()
        supervisorExit()
        println("Docker operations regression: $checks checks passed")
    }

    private fun longRetryProgress() {
        HealthStatus.resetSession()
        States.networkStatus = com.rsplwe.esurfing.utils.ConnectivityStatus.REQUEST_ERROR
        States.isRunning = true
        val worker = Thread(Client(Options("fixture", "fixture")))
        worker.start()
        try {
            var values: Map<String, Any?> = emptyMap()
            repeat(100) {
                HealthStatus.write()
                values = StateJson.decode(StateFiles.read(File(States.rootDir, "health.json")))
                if (values["clientStage"] != "retry_wait") Thread.sleep(10)
            }
            verify("long intentional retry publishes progress", values["clientStage"] == "retry_wait" && worker.isAlive)
            verify("retry stage refresh is bounded", values["clientStageTimeoutSeconds"] == 20L)
        } finally {
            States.isRunning = false
            worker.interrupt()
            worker.join(3000)
            States.networkStatus = com.rsplwe.esurfing.utils.ConnectivityStatus.DEFAULT
            States.isRunning = true
        }
        verify("retry can stop promptly", !worker.isAlive)
    }

    private fun supervisorExit() {
        val directory = File(root, "stall-child").apply { mkdir() }
        val executable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val builder = ProcessBuilder(executable.path, "-cp", System.getProperty("java.class.path"),
            OperationsRegression::class.java.name, "--stall-child")
        builder.environment()["STATE_DIR"] = directory.absolutePath
        builder.environment()["HEALTH_WRITE_INTERVAL_SECONDS"] = "1"
        builder.redirectErrorStream(true).redirectOutput(File(directory, "process.log"))
        val process = builder.start()
        try {
            verify("stalled phase exits child process", process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 1)
            val values = StateJson.decode(StateFiles.read(File(directory, "health.json")))
            verify("timeout recorded before exit", values["lastError"] == "CLIENT_STAGE_TIMEOUT stage=fixture_stall" && values["clientThreadAlive"] == false)
            verify("deadlock-prone shutdown hook bypassed", !File(directory, "unexpected-hook").exists())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    private fun jsonAndFiles() {
        val value = "中文\"\\\r\n\t" + 0.toChar() + 12.toChar()
        val parsed = StateJson.decode(StateJson.encode(linkedMapOf("text" to value, "number" to Long.MAX_VALUE, "flag" to true, "empty" to null)))
        verify("state JSON scalar round trip", parsed["text"] == value && parsed["number"] == Long.MAX_VALUE && parsed["flag"] == true && parsed["empty"] == null)
        for (invalid in listOf("", "{", "{}trailing", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "{\"a\":01}", "{\"a\":9223372036854775808}", "{\"a\":\"raw\nnewline\"}")) {
            verify("malformed JSON rejected", runCatching { StateJson.decode(invalid) }.isFailure)
        }
        val file = File(root, "atomic.json")
        StateFiles.write(file, StateJson.encode(mapOf("sequence" to 0, "value" to "0")))
        val running = AtomicBoolean(true); val failure = AtomicReference<Throwable?>()
        val reader = thread {
            while (running.get()) try {
                val values = StateJson.decode(StateFiles.read(file))
                check(values["sequence"].toString() == values["value"])
            } catch (e: Throwable) { failure.set(e); break }
        }
        try { repeat(80) { StateFiles.write(file, StateJson.encode(mapOf("sequence" to it, "value" to it.toString()))) } }
        finally { running.set(false); reader.join(3000) }
        if (failure.get() != null) throw IllegalStateException("atomic reader failed", failure.get())
        verify("atomic reader sees complete snapshots", !reader.isAlive)
        val directory = File(root, "existing-directory").apply { mkdir() }
        try { StateFiles.write(directory, "not a file"); error("directory replaced") } catch (_: java.io.IOException) { }
        verify("failed atomic write preserves target and cleans temp", directory.isDirectory && root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    private fun samples() {
        fun setup(name: String): ReauthPlannerEngine {
            wall = base; nanos = 10_000_000_000L
            return engine(name).also { it.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same") }
        }
        val trusted = setup("trusted")
        advance(7200); trusted.observePortal("same"); trusted.recordHeartbeatFailure(true)
        trusted.recordAuthSuccess(AuthorizationTrigger.PORTAL, wall, "same")
        verify("correlated same-network sample accepted", trusted.snapshot().successfulAuthSamples == 1 && trusted.snapshot().lastObservedLifetimeSeconds == 7200L)
        for (scenario in listOf("initial", "scheduled", "no-heartbeat", "transport", "outage", "changed-ip", "clock-jump", "restart")) {
            var e = setup(scenario)
            if (scenario == "restart") e = engine(scenario)
            advance(7200, if (scenario == "clock-jump") 14400 else 7200)
            e.observePortal(if (scenario == "changed-ip") "different" else "same")
            if (scenario != "no-heartbeat") e.recordHeartbeatFailure(scenario != "transport")
            if (scenario == "outage") e.invalidateSample()
            val trigger = when (scenario) { "initial" -> AuthorizationTrigger.INITIAL; "scheduled" -> AuthorizationTrigger.SCHEDULED; else -> AuthorizationTrigger.PORTAL }
            e.recordAuthSuccess(trigger, wall, if (scenario == "changed-ip") "different" else "same")
            verify("untrusted $scenario sample ignored", e.snapshot().successfulAuthSamples == 0)
        }
        val disabled = setup("disabled-evidence")
        advance(1800); disabled.observePortal("same"); disabled.recordHeartbeatFailure(true)
        disabled.recordAuthSuccess(AuthorizationTrigger.PORTAL, wall, "same")
        verify("short interval never learned", disabled.snapshot().successfulAuthSamples == 0)
    }

    private fun scheduleAndState() {
        wall = base; nanos = 10_000_000_000L
        for ((start, end) in listOf(4 to 6, 22 to 6, 0 to 24, 4 to 4)) {
            val e = engine("window-$start-$end", start, end)
            for (offset in listOf(0L, 3600L, 20 * 3600L, 25 * 3600L)) for (estimate in listOf(3600L, 7200L, 48 * 3600L, 7 * 86400L)) {
                val auth = base + offset
                val next = e.computeNext(auth, estimate)
                verify("future bounded schedule $start/$end $offset/$estimate", next >= auth + ReauthPlannerEngine.MIN_REAUTH_GAP && next <= auth + estimate)
            }
        }
        val wrapped = engine("wrapped", 22, 6)
        verify("cross-midnight window boundaries", wrapped.isInWindow(base - 5 * 3600) && wrapped.isInWindow(base) && !wrapped.isInWindow(base + 2 * 3600))
        val e = engine("grace")
        e.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same")
        val next = e.snapshot().nextPlannedReauthAt
        wall = next
        verify("due plan triggers in safe window", e.shouldTrigger(wall))
        wall = base
        seed("outside", next = base + 3 * 3600)
        val outside = engine("outside")
        wall = base + 3 * 3600
        verify("outside window waits", outside.isWaiting(wall) && !outside.shouldTrigger(wall))
        advance(2 * 3600)
        verify("window grace permits overdue fallback", outside.shouldTrigger(wall) && !outside.isWaiting(wall))
        wall = base
        val off = engine("disabled", enabled = false)
        off.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same")
        advance(3 * 86400)
        verify("disabled planner never triggers", !off.shouldTrigger(wall) && !off.isWaiting(wall))
        wall = base
        val rollback = engine("rollback")
        rollback.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same")
        wall -= 300
        verify("rollback discards stale schedule", !rollback.shouldTrigger(wall) && rollback.snapshot().lastAuthSuccessAt == 0L)
        wall = base
        seed("legacy", schema = 1, next = base - 1800, observed = 3600, samples = 3)
        val migrated = engine("legacy").snapshot()
        verify("legacy past target repaired", migrated.nextPlannedReauthAt >= base + 1800 && migrated.successfulAuthSamples == 0 && migrated.lastObservedLifetimeSeconds == 0L)
        for (kind in listOf("future-auth", "future-update", "huge-next", "invalid-estimate", "negative-samples", "malformed", "duplicate", "oversized")) {
            wall = base
            seed(kind, auth = if (kind == "future-auth") base + 300 else base,
                updated = if (kind == "future-update") base + 300 else base,
                next = if (kind == "huge-next") Long.MAX_VALUE else base + 2700,
                estimate = if (kind == "invalid-estimate") 1 else 3600,
                samples = if (kind == "negative-samples") -1 else 0)
            val file = File(root, "$kind.json")
            if (kind == "malformed") file.writeText("{partial")
            if (kind == "duplicate") file.writeText(file.readText().replace("\"schemaVersion\": 2", "\"schemaVersion\": 2,\n  \"schemaVersion\": 2"))
            if (kind == "oversized") file.writeText("x".repeat(32769))
            val s = engine(kind).snapshot()
            verify("invalid $kind state rejected", s.lastAuthSuccessAt == 0L && s.estimatedLifetimeSeconds == ReauthPlannerEngine.DEFAULT_INTERVAL && s.nextPlannedReauthAt == 0L)
        }
        seed("write-failure")
        val previous = File(root, "write-failure.json").readText()
        val failed = engine("write-failure", writer = { _, _ -> throw java.io.IOException("fixture") })
        failed.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same")
        verify("failed persistence preserves old file and in-memory recovery", File(root, "write-failure.json").readText() == previous && failed.snapshot().nextPlannedReauthAt > wall)
    }
}
