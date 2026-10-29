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
        if (args.firstOrNull() == "--owner-child") {
            val ownership = CoreStateOwnership.acquire(States.rootDir) ?: kotlin.system.exitProcess(73)
            File(States.rootDir, "owner-ready").writeText("ready")
            Thread.sleep(30000)
            ownership.close()
            return
        }
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
        ownershipProcess()
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

    private fun ownershipProcess() {
        val directory = File(root, "ownership-process").apply { mkdir() }
        val executable = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        fun start(name: String): Process {
            val builder = ProcessBuilder(executable.path, "-cp", System.getProperty("java.class.path"), OperationsRegression::class.java.name, "--owner-child")
            builder.environment()["STATE_DIR"] = directory.absolutePath
            builder.redirectErrorStream(true).redirectOutput(File(directory, "$name.log"))
            return builder.start()
        }
        val first = start("first")
        var second: Process? = null
        try {
            repeat(100) { if (!File(directory, "owner-ready").exists()) Thread.sleep(30) }
            verify("real owner child acquired state", File(directory, "owner-ready").exists() && first.isAlive)
            second = start("second")
            verify("duplicate child cannot acquire live owner's directory", second.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && second.exitValue() == 73)
            verify("rejected child never publishes health", !File(directory, "health.json").exists())
            first.destroyForcibly()
            verify("force-killed owner actually exits", first.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && !first.isAlive)
            // Process signaling and the filesystem's final lock teardown need not become
            // visible in the same instruction on the Windows test host.
            var replacement: CoreStateOwnership? = null
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            while (replacement == null && System.nanoTime() < deadline) {
                replacement = CoreStateOwnership.acquire(directory)
                if (replacement == null) Thread.sleep(20)
            }
            replacement.use { verify("OS releases ownership after force-killed child", it != null) }
        } finally {
            if (first.isAlive) first.destroyForcibly()
            second?.let { if (it.isAlive) it.destroyForcibly() }
        }
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
        wall = base
        val e = engine("daily-no-learning")
        e.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall, "same")
        advance(7200)
        e.observePortal("same"); e.recordHeartbeatFailure(true)
        e.recordAuthSuccess(AuthorizationTrigger.PORTAL, wall, "same")
        verify("daily plan never estimates session lifetime", e.snapshot().successfulAuthSamples == 0 && e.snapshot().estimatedLifetimeSeconds == 86400L)
    }

    private fun scheduleAndState() {
        wall = base - 3600 // 03:00 UTC+8
        val e = engine("daily")
        verify("before start waits", !e.isDue(wall) && e.isWaiting(wall))
        wall = base
        verify("starts at 04:00", e.isDue(wall) && e.shouldTrigger(wall))
        verify("one daily claim", !e.shouldTrigger(wall) && !e.isDue(wall + 30))
        verify("restart retains daily claim", !engine("daily").shouldTrigger(wall))
        wall += 86400
        verify("next day is eligible", engine("daily").shouldTrigger(wall))
        wall -= 86400
        verify("clock rollback never repeats a prior day", !engine("daily").shouldTrigger(wall))
        wall = base + 3 * 86400 + 3600
        verify("startup inside window catches up", engine("catchup").shouldTrigger(wall))
        wall = base + 2 * 3600
        verify("missed window never reauths during daytime", !engine("missed").shouldTrigger(wall) && engine("missed").snapshot().nextPlannedReauthAt == base + 86400)
        wall = base + 59
        verify("equal start/end is one minute fixed-time window", engine("fixed", 4, 4).shouldTrigger(wall))
        wall = base + 60
        verify("fixed time does not mean all day", !engine("fixed-missed", 4, 4).shouldTrigger(wall))
        rejected("end earlier than start") { engine("bad-window", 6, 4) }
        rejected("start outside day") { engine("bad-start", 24, 24) }
        rejected("end outside day") { engine("bad-end", 4, 25) }
        wall = base
        val normal = engine("normal-login")
        normal.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall)
        verify("successful ordinary login in window satisfies the day", !normal.shouldTrigger(wall))
        val off = engine("off", enabled = false)
        off.recordAuthSuccess(AuthorizationTrigger.PORTAL, wall)
        verify("off disables planned reauth only", !off.shouldTrigger(wall) && off.snapshot().lastAuthSuccessAt == wall)
        val failure = engine("failed-write", writer = { _, _ -> throw java.io.IOException("fixture") })
        verify("failed claim persistence cannot trigger", !failure.shouldTrigger(wall) && failure.isDue(wall))
        File(root, "corrupt.json").writeText("{broken")
        verify("corrupt ledger skips today rather than repeating", !engine("corrupt").shouldTrigger(wall))
        verify("repaired corrupt ledger can schedule tomorrow", engine("corrupt").shouldTrigger(wall + 86400))
        File(root, "empty-ledger.json").writeText("{}")
        verify("empty JSON is corrupt history and skips today", !engine("empty-ledger").shouldTrigger(wall))
        File(root, "future-schema.json").writeText("{\"schemaVersion\":999}")
        verify("unknown schema remains paused and is not overwritten", !engine("future-schema").shouldTrigger(wall)
            && engine("future-schema").snapshot().stateInvalid && File(root, "future-schema.json").readText().contains("999"))
        val offLogin = engine("off-login", enabled = false)
        offLogin.recordAuthSuccess(AuthorizationTrigger.INITIAL, wall)
        verify("ordinary login while disabled satisfies the day when reenabled", !engine("off-login").shouldTrigger(wall))
        seed("legacy", auth = base, next = base + 86400)
        verify("old plan migrates successful login without another forced login", !engine("legacy").shouldTrigger(wall))
        val directory = File(root, "owner").apply { mkdir() }
        val owner = CoreStateOwnership.acquire(directory)
        verify("first core acquires state ownership", owner != null)
        verify("second core cannot own same state", CoreStateOwnership.acquire(directory) == null)
        owner!!.close()
        CoreStateOwnership.acquire(directory).use { verify("released owner can be reacquired", it != null) }
        unownedOnline()
    }

    private fun unownedOnline() {
        HealthStatus.resetSession()
        HealthStatus.markNetworkCheckSuccess()
        States.cancelAuthorization()
        States.networkStatus = com.rsplwe.esurfing.utils.ConnectivityStatus.SUCCESS
        States.portalCandidate = PortalCandidate()
        States.isRunning = true
        val worker = Thread(Client(Options("fixture", "fixture")))
        worker.start()
        try {
            var health: Map<String, Any?> = emptyMap()
            repeat(100) {
                HealthStatus.write()
                health = StateJson.decode(StateFiles.read(File(States.rootDir, "health.json")))
                if (health["connectionState"] != "network-online-unowned") Thread.sleep(10)
            }
            verify("online without owned session is explicit", health["connectionState"] == "network-online-unowned" && health["networkReachable"] == true)
            verify("network availability does not fabricate authentication", !HealthStatus.authenticated && worker.isAlive)
        } finally {
            States.isRunning = false; worker.interrupt(); worker.join(3000)
            HealthStatus.networkReachable = false; States.networkStatus = com.rsplwe.esurfing.utils.ConnectivityStatus.DEFAULT; States.isRunning = true
        }
    }
}
