package com.rsplwe.esurfing

import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.utils.ConnectivityStatus
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.log4j.Appender
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.Priority
import org.apache.log4j.spi.LoggingEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 合成时钟与认证替身；不请求网络，也不实际等待摘要周期。 */
object HeartbeatLogRegression {
    private var checks = 0
    private data class Event(val level: Priority, val message: String)
    private fun verify(label: String, condition: Boolean) { check(condition) { label }; checks++ }
    private fun snapshot(events: MutableList<Event>): List<Event> = synchronized(events) { events.toList() }

    private fun capture(
        name: String,
        observe: (Event) -> Unit = {},
        action: (Logger, MutableList<Event>) -> Unit,
    ) {
        val logger = Logger.getLogger(name)
        val previousLevel = logger.level
        val previousAdditivity = logger.additivity
        val previousAppenders = mutableListOf<Appender>()
        val enumeration = logger.allAppenders
        while (enumeration.hasMoreElements()) previousAppenders += enumeration.nextElement() as Appender
        val events = Collections.synchronizedList(mutableListOf<Event>())
        val appender = object : AppenderSkeleton() {
            override fun append(event: LoggingEvent) {
                val recorded = Event(event.level, event.renderedMessage)
                events += recorded
                observe(recorded)
            }
            override fun close() = Unit
            override fun requiresLayout() = false
        }
        // 逐一移除，避免 removeAllAppenders 关闭原有 appender。
        previousAppenders.forEach(logger::removeAppender)
        logger.level = Level.INFO
        logger.additivity = false
        logger.addAppender(appender)
        try { action(logger, events) }
        finally {
            logger.removeAppender(appender)
            previousAppenders.forEach(logger::addAppender)
            logger.level = previousLevel
            logger.additivity = previousAdditivity
            appender.close()
        }
    }

    fun run(): Int {
        checks = 0
        summaryAndDebug()
        clientRecovery()
        return checks
    }

    private fun summaryAndDebug() = capture("com.rsplwe.esurfing.test.HeartbeatLogRegression") { logger, events ->
        var nanos = 0L
        val minute = TimeUnit.MINUTES.toNanos(1)
        val heartbeat = HeartbeatLog(logger) { nanos }
        heartbeat.attempt()
        verify("INFO suppresses heartbeat attempts", snapshot(events).isEmpty())
        heartbeat.success(0, 15)
        verify("first successful heartbeat reports immediately even at clock zero",
            snapshot(events) == listOf(Event(Level.INFO, "HEARTBEAT_SUMMARY successes=1 nextRetry=15s")))

        for (at in listOf(minute, 29 * minute, 30 * minute - 1)) {
            nanos = at
            heartbeat.attempt()
            heartbeat.success(0, 15)
        }
        verify("successful heartbeats before thirty minutes do not flood INFO", snapshot(events).size == 1)
        nanos = 30 * minute
        heartbeat.success(0, 20)
        verify("thirty minute boundary includes all successes since prior report",
            snapshot(events).last() == Event(Level.INFO, "HEARTBEAT_SUMMARY successes=4 nextRetry=20s"))
        nanos += minute
        heartbeat.success(0, 20)
        verify("summary resets its reporting window", snapshot(events).size == 2)
        nanos = 60 * minute
        heartbeat.success(0, 20)
        verify("next summary resets its success count",
            snapshot(events).last().message == "HEARTBEAT_SUMMARY successes=2 nextRetry=20s")

        nanos = 90 * minute
        val beforeRecovery = snapshot(events).size
        heartbeat.success(2, 5)
        verify("recovery on summary boundary emits exactly one record", snapshot(events).size == beforeRecovery + 1)
        verify("recovery takes priority over an overdue summary",
            snapshot(events).last() == Event(Level.INFO, "HEARTBEAT_RECOVERED failures=2 nextRetry=5s"))
        nanos++
        heartbeat.success(0, 5)
        nanos = 120 * minute - 1
        heartbeat.success(0, 5)
        verify("steady success does not repeat recovery or emit early summary",
            snapshot(events).size == beforeRecovery + 1 && snapshot(events).count { it.message.startsWith("HEARTBEAT_RECOVERED") } == 1)
        nanos = 120 * minute
        heartbeat.success(0, 5)
        verify("summary period restarts from recovery",
            snapshot(events).last().message == "HEARTBEAT_SUMMARY successes=3 nextRetry=5s")
        heartbeat.success(0, 5)
        heartbeat.reset()
        heartbeat.success(0, 10)
        verify("new login reset immediately reports its first successful heartbeat",
            snapshot(events).last().message == "HEARTBEAT_SUMMARY successes=1 nextRetry=10s")
        verify("INFO mode never records per attempt or per success DEBUG events",
            snapshot(events).all { it.level == Level.INFO })

        events.clear()
        logger.level = Level.DEBUG
        heartbeat.reset()
        heartbeat.attempt()
        heartbeat.success(0, 7)
        nanos++
        heartbeat.attempt()
        heartbeat.success(0, 9)
        val debug = snapshot(events)
        verify("DEBUG logs every heartbeat attempt", debug.count { it.level == Level.DEBUG && it.message == "HEARTBEAT_ATTEMPT" } == 2)
        verify("DEBUG logs every successful heartbeat and next interval",
            debug.filter { it.level == Level.DEBUG && it.message.startsWith("HEARTBEAT_SUCCESS") }.map { it.message } ==
                listOf("HEARTBEAT_SUCCESS nextRetry=7s", "HEARTBEAT_SUCCESS nextRetry=9s"))
        verify("DEBUG ordering retains the first INFO summary", debug == listOf(
            Event(Level.DEBUG, "HEARTBEAT_ATTEMPT"),
            Event(Level.DEBUG, "HEARTBEAT_SUCCESS nextRetry=7s"),
            Event(Level.INFO, "HEARTBEAT_SUMMARY successes=1 nextRetry=7s"),
            Event(Level.DEBUG, "HEARTBEAT_ATTEMPT"),
            Event(Level.DEBUG, "HEARTBEAT_SUCCESS nextRetry=9s"),
        ))
        events.clear()
        logger.level = Level.INFO
        heartbeat.reset()
        repeat(2) { heartbeat.attempt(); heartbeat.success(0, 5); nanos++ }
        verify("returning to INFO suppresses DEBUG immediately",
            snapshot(events) == listOf(Event(Level.INFO, "HEARTBEAT_SUMMARY successes=1 nextRetry=5s")))
    }

    private fun clientRecovery() {
        // 阈值 1 不存在亚阈值失败场景；普通回归配置使用默认阈值 3。
        if (RuntimeConfig.heartbeatFailureThreshold < 2) return
        val failed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        capture(Client::class.java.name, { event ->
            if (event.level == Level.WARN && event.message.startsWith("HEARTBEAT_FAILED")) failed.countDown()
            if (event.level == Level.INFO && event.message.startsWith("HEARTBEAT_RECOVERED")) recovered.countDown()
        }) { _, events ->
            val oldRunning = States.isRunning
            val oldCandidate = States.portalCandidate
            val oldNetwork = States.networkStatus
            val oldTicket = States.ticket
            val oldAlgorithm = States.algoId
            val oldAuthenticated = HealthStatus.authenticated
            val oldLoginAt = HealthStatus.lastLoginSuccessAt
            val oldHeartbeatAt = HealthStatus.lastHeartbeatSuccessAt
            val oldError = HealthStatus.lastError
            val oldFailures = HealthStatus.consecutiveHeartbeatFailures.get()
            val oldPortalDetections = HealthStatus.consecutivePortalDetections.get()
            val oldThreadAlive = HealthStatus.clientThreadAlive
            val keepCalls = AtomicInteger()
            val frees = AtomicInteger()
            var bootstrap = true
            val fake = object : AuthenticationSession {
                override fun getSessionId() = 17L
                override fun encrypt(value: String) = value
                override fun decrypt(value: String) = value
                override fun free() { frees.incrementAndGet() }
            }
            val client = Client(Options("synthetic-user", "synthetic-password"), { fake }, { url, _, _ ->
                val response = when {
                    bootstrap -> { bootstrap = false; "synthetic bootstrap" }
                    url.startsWith("http://ticket.test/") -> "<response><ticket>synthetic-ticket</ticket></response>"
                    url.startsWith("http://auth.test/") -> "<response><keep-url>http://keep.test/keep</keep-url><term-url>http://term.test/term</term-url><keep-retry>5</keep-retry></response>"
                    url.startsWith("http://keep.test/") -> if (keepCalls.incrementAndGet() == 2)
                        "<response><error>synthetic failure</error></response>"
                    else "<response><interval>5</interval></response>"
                    else -> "terminated"
                }
                NetResult.Success(response.toResponseBody())
            })
            val worker = Thread(client, "heartbeat-log-regression")
            val authorization = Client::class.java.getDeclaredMethod("authorization", AuthorizationTrigger::class.java)
                .apply { isAccessible = true }
            fun awaitHeartbeat(label: String, condition: () -> Boolean) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!condition() && worker.isAlive && System.nanoTime() < deadline) {
                    // 重复设置直到完成，消除日志通知与 worker 更新 tick 的先后竞争。
                    client.tick = 0
                    Thread.sleep(10)
                }
                verify(label, condition())
            }
            try {
                States.cancelAuthorization()
                States.isRunning = true
                States.networkStatus = ConnectivityStatus.SUCCESS
                States.portalCandidate = PortalCandidate(portalBody = """<!--//config.campus.js.chinatelecom.com
                    <auth-url><![CDATA[http://auth.test/auth.cgi]]></auth-url>
                    <ticket-url><![CDATA[http://ticket.test/ticket.cgi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1&portal_node=test]]></ticket-url>
                    //config.campus.js.chinatelecom.com-->""".trimIndent())
                HealthStatus.resetSession()
                authorization.invoke(client, AuthorizationTrigger.INITIAL)
                verify("synthetic login is confirmed before testing periodic heartbeat", HealthStatus.authenticated && keepCalls.get() == 1)
                worker.start()
                awaitHeartbeat("periodic heartbeat failure remains WARN", { failed.count == 0L })
                verify("subthreshold heartbeat failure preserves authenticated session",
                    HealthStatus.consecutiveHeartbeatFailures.get() == 1 && HealthStatus.authenticated && client.session != null)
                verify("failed heartbeat retains diagnostic health error", HealthStatus.lastError?.startsWith("heartbeat failed:") == true)

                HealthStatus.lastHeartbeatSuccessAt = 0
                awaitHeartbeat("successful heartbeat reports recovery immediately", { recovered.count == 0L && HealthStatus.lastHeartbeatSuccessAt > 0 })
                verify("recovered heartbeat clears failure count and error",
                    HealthStatus.consecutiveHeartbeatFailures.get() == 0 && HealthStatus.lastError == null && HealthStatus.authenticated)
                verify("recovery log retains the number of preceding failures",
                    snapshot(events).any { it == Event(Level.INFO, "HEARTBEAT_RECOVERED failures=1 nextRetry=5s") })

                HealthStatus.lastHeartbeatSuccessAt = 0
                HealthStatus.consecutivePortalDetections.set(1)
                HealthStatus.markError("synthetic stale error")
                awaitHeartbeat("every steady heartbeat still refreshes health time", {
                    keepCalls.get() >= 4 && HealthStatus.lastHeartbeatSuccessAt > 0 && HealthStatus.lastError == null
                })
                verify("steady heartbeat keeps health counters clear",
                    HealthStatus.consecutiveHeartbeatFailures.get() == 0 && HealthStatus.consecutivePortalDetections.get() == 0)
                val heartbeatInfo = snapshot(events).filter { it.level == Level.INFO && it.message.startsWith("HEARTBEAT_") }
                verify("steady client heartbeat does not repeat recovery or early summary",
                    heartbeatInfo.size == 1 && heartbeatInfo.single().message.startsWith("HEARTBEAT_RECOVERED"))
                verify("Client INFO output excludes per heartbeat DEBUG records", snapshot(events).none { it.level == Level.DEBUG })
            } finally {
                States.isRunning = false
                worker.interrupt()
                worker.join(3000)
                client.shutdown()
                States.cancelAuthorization()
                States.portalCandidate = oldCandidate
                States.networkStatus = oldNetwork
                States.ticket = oldTicket
                States.algoId = oldAlgorithm
                States.isRunning = oldRunning
                HealthStatus.authenticated = oldAuthenticated
                HealthStatus.lastLoginSuccessAt = oldLoginAt
                HealthStatus.lastHeartbeatSuccessAt = oldHeartbeatAt
                HealthStatus.lastError = oldError
                HealthStatus.consecutiveHeartbeatFailures.set(oldFailures)
                HealthStatus.consecutivePortalDetections.set(oldPortalDetections)
                HealthStatus.clientThreadAlive = oldThreadAlive
            }
            verify("heartbeat worker stops and frees the fake session exactly once", !worker.isAlive && frees.get() == 1)
        }
    }
}
