package com.rsplwe.esurfing

import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DailyAndOwnershipRegression {
    fun run(): Int {
        var checks = 0
        fun verify(value: Boolean, label: String) { check(value) { label }; checks++ }
        fun epoch(value: String) = OffsetDateTime.parse(value + "+08:00").toEpochSecond()
        val root = File(States.rootDir, "daily-and-owner").apply { mkdirs() }
        val file = File(root, "plan.json").apply { delete() }
        var now = epoch("2026-10-29T03:59:59")
        val engine = DailyReauthEngine(file, true, 4, 6, { now })
        verify(!engine.isDue() && engine.snapshot().nextAt == now + 1, "Daily plan waits for UTC+8 window start")
        now++
        verify(engine.isDue(), "Daily plan becomes due at start without 45-hour lifetime")
        engine.waitingForContext()
        verify(engine.snapshot().status == "waiting-for-portal" && engine.isDue(), "Missing portal does not consume daily claim")
        verify(engine.claim() && !engine.claim(), "Plan claims once and persists before authorization")
        verify(file.readText().contains("lastClaimedDay"), "Claim ledger contains a durable day")
        val restarted = DailyReauthEngine(file, true, 4, 6, { now })
        verify(!restarted.isDue(), "Process restart cannot duplicate today's forced reauthentication")
        now = epoch("2026-10-30T04:30:00")
        verify(restarted.isDue(), "Next calendar day has a new opportunity")
        restarted.recordAuthSuccess()
        verify(!restarted.isDue(), "Successful ordinary login in window fulfills today's refresh")
        now = epoch("2026-10-29T04:30:00")
        verify(!restarted.isDue(), "Clock rollback cannot replay previously claimed days")
        now = epoch("2026-10-31T06:00:00")
        verify(!restarted.isDue() && restarted.snapshot().nextAt == epoch("2026-11-01T04:00:00"), "Missed window never forces a daytime login")
        restarted.configure(false, 4, 6)
        now = epoch("2026-11-01T04:30:00")
        verify(!restarted.isDue() && restarted.snapshot().nextAt == 0L, "Switch disables only daily scheduling")
        restarted.configure(true, 4, 6)
        verify(restarted.isDue(), "Switch can enable current window")
        val invalid = runCatching { restarted.configure(true, 6, 4) }
        verify(invalid.isFailure && restarted.snapshot().startHour == 4, "Inverted window is rejected atomically")
        restarted.configure(true, 4, 4)
        now = epoch("2026-11-02T04:00:30")
        verify(restarted.isDue(), "Equal bounds mean a fixed time with one-minute polling tolerance")
        now = epoch("2026-11-02T04:01:00")
        verify(!restarted.isDue(), "Equal bounds never mean an all-day window")
        restarted.configure(true, 23, 24)
        now = epoch("2026-11-02T23:59:59")
        verify(restarted.isDue(), "End at 24:00 supports the last hour")

        val failed = DailyReauthEngine(File(root, "failed.json"), true, 4, 6, { epoch("2026-11-03T04:00:00") },
            { _, _ -> throw java.io.IOException("synthetic") })
        verify(!failed.claim() && failed.snapshot().status == "state-write-failed", "Failed durable claim cannot authorize a logout")
        val bad = File(root, "bad.json").apply { writeText("broken") }
        val corrupt = DailyReauthEngine(bad, true, 4, 6, { epoch("2026-11-03T04:00:00") })
        verify(!corrupt.isDue(), "Corrupt ledger fails closed for this day instead of duplicate logout")
        val future = File(root, "future.json").apply { writeText("{\"schemaVersion\":2,\"futureField\":true}") }
        val futureText = future.readText()
        val unsupported = DailyReauthEngine(future, true, 4, 6, { epoch("2026-11-03T04:00:00") })
        verify(!unsupported.isDue() && unsupported.snapshot().status == "state-schema-unsupported",
            "Future state schema explicitly pauses daily scheduling")
        unsupported.recordAuthSuccess()
        verify(!unsupported.claim() && future.readText() == futureText,
            "Unknown future ledger is retained unchanged even after an ordinary login")
        val notJson = File(root, "not-json.json").apply {
            writeText("prefix \"schemaVersion\":1,\"lastClaimedDay\":-1,\"updatedAt\":1 suffix")
        }
        val invalidObject = DailyReauthEngine(notJson, true, 4, 6, { epoch("2026-11-03T04:00:00") })
        verify(!invalidObject.isDue(), "Embedded fields outside a complete JSON object do not create a valid ledger")

        val ownerRoot = File(root, "owner").apply { mkdirs() }
        val java = File(System.getProperty("java.home"), "bin/java.exe").let { if (it.exists()) it else File(it.parentFile, "java") }.absolutePath
        fun process() = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CoreOwnershipProbe::class.java.name, ownerRoot.absolutePath)
            .redirectErrorStream(true).start()
        File(ownerRoot, "ready").delete()
        val owner = process()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!File(ownerRoot, "ready").exists() && owner.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            verify(owner.isAlive && File(ownerRoot, "ready").exists(), "Real subprocess holds state ownership")
            val competing = process()
            try {
                verify(competing.waitFor(10, TimeUnit.SECONDS) && competing.exitValue() == 73, "Competing JVM exits with ownership conflict code")
                verify(competing.inputStream.bufferedReader().readText().contains("CORE_STATE_IN_USE"), "Conflict is diagnosed without writing health")
            } finally { if (competing.isAlive) competing.destroyForcibly().waitFor() }
            owner.outputStream.close()
            verify(owner.waitFor(10, TimeUnit.SECONDS) && owner.exitValue() == 0, "Stdin EOF stops the real owner subprocess")
            CoreStateOwnership.acquire(ownerRoot).use { verify(it != null, "EOF completion releases lifetime state lock") }
        } finally { if (owner.isAlive) owner.destroyForcibly().waitFor() }

        val watched = process()
        val parentExited = AtomicBoolean(false)
        try {
            CoreParentMonitor.watch(watched.pid()) { parentExited.set(true) }
            verify(!parentExited.get(), "Live owner does not trigger parent-death fallback")
            watched.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!parentExited.get() && System.nanoTime() < deadline) Thread.sleep(10)
            verify(parentExited.get(), "Owner exit triggers real ProcessHandle parent-death fallback")
        } finally { if (watched.isAlive) watched.destroyForcibly().waitFor() }

        var stopped = false
        var commands = 0
        WindowsControl.run({ "stop" }, { stopped = true }, { commands++ })
        verify(stopped && commands == 0, "Explicit stop and EOF use the same control stop callback")
        val portal = com.rsplwe.esurfing.utils.NetworkConnectivityResult(
            com.rsplwe.esurfing.utils.ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP, portalUrl = "http://synthetic.test/portal")
        States.cancelAuthorization()
        States.requestAuthorization(portal, scheduled = true)
        val cancelledVersion = States.authorizationRequestVersion
        States.cancelScheduledAuthorization()
        verify(!States.forceAuthorization, "Turning scheduling off cancels an unstarted scheduled request")
        verify(!States.markAuthorizationStarted(cancelledVersion), "A cancelled scheduled snapshot cannot start after its switch is off")
        States.requestAuthorization(portal)
        States.cancelScheduledAuthorization()
        verify(States.forceAuthorization, "Turning scheduling off preserves ordinary network recovery")
        States.cancelAuthorization()
        States.requestAuthorization(portal, scheduled = true)
        verify(States.markAuthorizationStarted(States.authorizationRequestVersion), "Current scheduled request starts atomically before becoming ordinary recovery")
        States.cancelScheduledAuthorization()
        verify(States.forceAuthorization && States.authorizationRequest()?.scheduled == false,
            "Once a scheduled login starts, disabling scheduling cannot erase its ordinary retry")
        States.cancelAuthorization()
        HealthStatus.authenticated = false
        HealthStatus.markNetworkCheckSuccess()
        HealthStatus.markUnownedNetworkWait()
        HealthStatus.write()
        val health = File(States.rootDir, RuntimeConfig.healthFileName).readText()
        verify(health.contains("\"networkReachable\": true") && health.contains("network-online-unowned") &&
            health.contains("\"authenticated\": false"), "Network availability is explicit without claiming authentication")
        HealthStatus.markNetworkCheckNotReachable()
        verify(!HealthStatus.networkReachable, "Failed/redirected probe clears network reachability")
        return checks
    }
}

/** Uses real process pipes and FileLock, with no native loader, network or credentials. */
object CoreOwnershipProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single())
        val owner = CoreStateOwnership.acquire(root)
        if (owner == null) { println("CORE_STATE_IN_USE"); kotlin.system.exitProcess(73) }
        owner.use {
            File(root, "ready").writeText("ready")
            WindowsControl.run(::readlnOrNull, {}, {})
        }
    }
}
