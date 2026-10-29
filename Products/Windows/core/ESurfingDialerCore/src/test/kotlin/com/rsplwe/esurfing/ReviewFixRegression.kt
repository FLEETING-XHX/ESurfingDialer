package com.rsplwe.esurfing

import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.rsplwe.esurfing.utils.*

object ReviewFixRegression {
    fun run(): Int {
        var checks = 0
        fun verify(value: Boolean, name: String) { check(value) { name }; checks++; println("PASS: $name") }
        val root = File(States.rootDir, "review-fixes-${UUID.randomUUID()}").apply { mkdirs() }
        val now = OffsetDateTime.parse("2026-10-29T04:30:00+08:00").toEpochSecond()
        val portal = NetworkConnectivityResult(ConnectivityStatus.IS_REDIRECTS_FOUND_IP, "192.0.2.10", "192.0.2.1")
        States.isRunning = true
        States.cancelAuthorization()
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val configuring = CountDownLatch(1)
        val configured = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val engine = DailyReauthEngine(File(root, "race.json"), true, 4, 6, { now }) { file, text ->
            writing.countDown()
            check(finishWrite.await(5, TimeUnit.SECONDS))
            file.writeText(text)
        }
        val coordinator = DailyReauthCoordinator(engine)
        val submitter = Thread {
            try { check(coordinator.request(portal)) } catch (e: Throwable) { failure.set(e) }
        }
        val controller = Thread {
            configuring.countDown()
            try { coordinator.configure(false, 4, 6) } catch (e: Throwable) { failure.set(e) }
            finally { configured.countDown() }
        }
        submitter.start()
        try {
            verify(writing.await(3, TimeUnit.SECONDS), "Daily claim reaches controlled durable-write boundary")
            controller.start()
            verify(configuring.await(3, TimeUnit.SECONDS) && !configured.await(100, TimeUnit.MILLISECONDS),
                "Disable cannot complete between durable claim and enqueue")
        } finally { finishWrite.countDown(); submitter.join(5000); if (controller.state != Thread.State.NEW) controller.join(5000) }
        failure.get()?.let { throw it }
        verify(!submitter.isAlive && !controller.isAlive && configured.count == 0L, "Both scheduler/control workers finish")
        verify(!States.forceAuthorization && !States.markAuthorizationStarted(States.authorizationRequestVersion),
            "After disable returns no old scheduled request can begin")
        verify(!coordinator.request(portal), "Disabled coordinator cannot enqueue a new request")

        val otherEngine = DailyReauthEngine(File(root, "window.json"), true, 4, 6, { now })
        val other = DailyReauthCoordinator(otherEngine)
        verify(other.request(portal), "Enabled coordinator enqueues one daily request")
        other.configure(true, 4, 6)
        verify(States.forceAuthorization, "Reapplying identical settings does not cancel a pending plan")
        other.configure(true, 8, 10)
        verify(!States.forceAuthorization, "Changing the time window cancels an unstarted old plan")
        verify(!DailyReauthEngine(File(root, "window.json"), true, 4, 6, { now }).isDue(),
            "Cancellation never rolls back the durable once-per-day claim")
        States.requestAuthorization(portal, scheduled = true)
        States.requestAuthorization(portal)
        other.configure(false, 8, 10)
        verify(States.forceAuthorization && States.authorizationRequest()?.scheduled == false,
            "Ordinary same-portal recovery supersedes scheduled ownership and survives disable")
        States.cancelAuthorization()
        States.requestAuthorization(portal, scheduled = true)
        verify(States.markAuthorizationStarted(States.authorizationRequestVersion), "Scheduled authentication starts under the request monitor")
        other.configure(false, 8, 10)
        verify(States.forceAuthorization, "Disabling does not cancel already-started ordinary retry")
        States.cancelAuthorization()

        fun folder(name: String) = File(root, name).apply { mkdirs() }
        fun state(dir: File) = File(dir, "device-state.json")
        val fresh = folder("fresh")
        val identity = DeviceIdentityRepository(fresh).load()
        val saved = state(fresh).readBytes()
        verify(identity == DeviceIdentityRepository(fresh).load() && state(fresh).readBytes().contentEquals(saved),
            "Fresh identity remains byte-for-byte stable on restart")
        verify(File(fresh, "device-state.json.bak").isFile, "Initial identity has a durable recovery backup")
        val broken = "{\"macAddress\":\"02:00:00:00:00:01\",\"clientId\":\""
        state(fresh).writeText(broken)
        verify(DeviceIdentityRepository(fresh).load() == identity, "Truncated primary restores the original complete identity")
        verify(fresh.listFiles()!!.any { it.name.startsWith("device-state.json.corrupt-") && it.readText() == broken },
            "Recovery retains the damaged primary unchanged as evidence")
        state(fresh).delete()
        verify(DeviceIdentityRepository(fresh).load() == identity, "Missing primary restores existing backup instead of inventing an ID")
        for ((index, text) in listOf(broken, "{}", "prefix \"macAddress\":\"02:00:00:00:00:01\",\"clientId\":\"old\"", "{\"macAddress\":\"invalid\",\"clientId\":\"old\"}").withIndex()) {
            val dir = folder("bad-$index")
            state(dir).writeText(text)
            verify(runCatching { DeviceIdentityRepository(dir).load() }.isFailure && state(dir).readText() == text,
                "Invalid identity without backup fails without replacing original $index")
        }
        val explicit = folder("override")
        state(explicit).writeText(broken)
        verify(runCatching { DeviceIdentityRepository(explicit).load("02:00:00:00:00:01", null) }.isFailure,
            "Partial environment override cannot invent a missing identity field")
        val override = DeviceIdentityRepository(explicit).load("02:00:00:00:00:01", "Legacy-ID.123")
        verify(override.clientId == "legacy-id.123", "Complete explicit override accepts a safe legacy header ID")
        val beforeInvalidEnv = state(explicit).readBytes()
        verify(runCatching { DeviceIdentityRepository(explicit).load(null, "bad\"id") }.isFailure &&
            state(explicit).readBytes().contentEquals(beforeInvalidEnv), "Unsafe environment ID is rejected before disk writes")
        val failureDir = folder("write-failure")
        state(failureDir).writeBytes(saved)
        verify(runCatching { DeviceIdentityRepository(failureDir) { _, _ -> throw java.io.IOException("synthetic") }.load() }.isFailure &&
            state(failureDir).readBytes().contentEquals(saved), "Backup write failure preserves the valid primary")
        verify(runCatching { DeviceIdentityRepository(failureDir) { file, text ->
            if (file.name == "device-state.json") throw java.io.IOException("synthetic primary failure")
            file.writeText(text)
        }.load("02:00:00:00:00:02", null) }.isFailure && state(failureDir).readBytes().contentEquals(saved),
            "Primary replacement failure preserves old primary and recoverable backup")
        verify(DeviceIdentityRepository(failureDir).load() == identity, "Restart after failed update retains the original identity")
        return checks
    }
}
