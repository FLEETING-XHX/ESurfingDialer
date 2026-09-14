package com.rsplwe.esurfing

import com.rsplwe.esurfing.States.isRunning
import com.rsplwe.esurfing.States.ticket
import com.rsplwe.esurfing.hook.Session
import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.utils.ConnectivityStatus.DEFAULT
import com.rsplwe.esurfing.utils.ConnectivityStatus.IS_REDIRECTS_FOUND_IP
import com.rsplwe.esurfing.utils.ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP
import com.rsplwe.esurfing.utils.ConnectivityStatus.REQUEST_ERROR
import com.rsplwe.esurfing.utils.ConnectivityStatus.SUCCESS
import com.rsplwe.esurfing.utils.getTime
import org.apache.log4j.Logger
import java.lang.Thread.sleep
import kotlin.system.exitProcess

class Client(private val options: Options) : Runnable {

    private val logger: Logger = Logger.getLogger(Client::class.java)
    private var keepUrl = ""
    private var termUrl = ""
    private var keepRetrySeconds = RuntimeConfig.loginRetryInitialSeconds
    private var loginFailures = 0
    private var abnormalRecoveries = 0

    var session: Session? = null

    @Volatile
    var tick: Long = 0

    override fun run() {
        HealthStatus.clientThreadAlive = true
        logger.info("APPLICATION_STARTED")
        try {
            while (isRunning) {
                try {
                    runClientIteration()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("CLIENT_THREAD_INTERRUPTED")
                    break
                } catch (e: Exception) {
                    HealthStatus.markError("client loop recovered: ${e.message}")
                    logger.error("CLIENT_LOOP_RECOVERED", e)
                    resetSessionState("unhandled exception in client loop", countAbnormalRecovery = true)
                    sleep(nextLoginRetrySeconds() * 1000)
                }
            }
        } finally {
            HealthStatus.clientThreadAlive = false
            HealthStatus.write()
            logger.warn("CLIENT_THREAD_EXITED")
        }
    }

    private fun runClientIteration() {
        when {
            States.networkStatus == DEFAULT -> {
                sleep(1000)
            }

            States.networkStatus == REQUEST_ERROR -> {
                sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
            }

            States.networkStatus == IS_REDIRECTS_NOT_FOUND_IP -> {
                HealthStatus.markError("portal redirect missing user/ac ip")
                sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
            }

            States.forceAuthorization || States.networkStatus == IS_REDIRECTS_FOUND_IP -> {
                authorization()
            }

            States.networkStatus == SUCCESS -> {
                if (session != null && HealthStatus.authenticated) {
                    maybeHeartbeat()
                }
                sleep(500)
            }
        }
    }

    private fun maybeHeartbeat() {
        if ((System.currentTimeMillis() - tick) < keepRetrySeconds * 1000) return

        logger.info("HEARTBEAT_ATTEMPT")
        try {
            heartbeat(ticket)
            HealthStatus.markHeartbeatSuccess()
            logger.info("HEARTBEAT_SUCCESS nextRetry=${keepRetrySeconds}s")
        } catch (e: Exception) {
            val failures = HealthStatus.consecutiveHeartbeatFailures.incrementAndGet()
            HealthStatus.markError("heartbeat failed: ${e.message}")
            logger.warn("HEARTBEAT_FAILED count=$failures: ${e.message}", e)

            if (failures >= RuntimeConfig.heartbeatFailureThreshold) {
                resetSessionState("heartbeat failure threshold reached", countAbnormalRecovery = true)
                States.forceAuthorization = true
                States.networkStatus = DEFAULT
            } else {
                keepRetrySeconds = (failures * 5L).coerceAtMost(30)
            }
        } finally {
            tick = System.currentTimeMillis()
        }
    }

    private fun authorization() {
        resetSessionState("starting authorization", countAbnormalRecovery = false)
        States.algoId = "00000000-0000-0000-0000-000000000000"

        logger.info("LOGIN_ATTEMPT userIp=${States.userIp} acIp=${States.acIp}")
        initSession()
        if ((session?.getSessionId() ?: 0) == 0L) {
            HealthStatus.markError("failed to initialize session")
            logger.error("LOGIN_FAILED failed to initialize session")
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }

        logger.info("Session ID: ${session?.getSessionId()}")
        ticket = getTicket()
        logger.info("Ticket: ${maskSecret(ticket)}")
        login()

        if (keepUrl.isEmpty()) {
            HealthStatus.markError("keepUrl is empty")
            logger.error("LOGIN_FAILED KeepUrl is empty")
            resetSessionState("empty keepUrl", countAbnormalRecovery = true)
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }

        States.forceAuthorization = false
        States.networkStatus = SUCCESS
        loginFailures = 0
        tick = System.currentTimeMillis()
        HealthStatus.markLoginSuccess()
        logger.info("LOGIN_SUCCESS")
    }

    private fun initSession() {
        when (val result = post(States.ticketUrl, States.algoId)) {
            is NetResult.Success -> {
                session = Session(result.data.bytes())
            }

            is NetResult.Error -> {
                throw IllegalStateException(result.exception)
            }
        }
    }

    private fun getTicket(): String {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${Constants.USER_AGENT}</user-agent>
                <client-id>${States.clientId}</client-id>
                <local-time>${getTime()}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${States.userIp}</ipv4>
                <ipv6></ipv6>
                <mac>${States.macAddress}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(States.ticketUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(result.data.string())
                val value = data.substringAfter("<ticket>").substringBefore("</ticket>")
                if (value.isBlank() || value == data) throw IllegalStateException("ticket missing in response")
                return value
            }

            is NetResult.Error -> {
                throw IllegalStateException(result.exception)
            }
        }
    }

    private fun login() {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${Constants.USER_AGENT}</user-agent>
                <client-id>${States.clientId}</client-id>
                <local-time>${getTime()}</local-time>
                <ticket>${ticket}</ticket>
                <userid>${options.loginUser}</userid>
                <passwd>${options.loginPassword}</passwd>
            </request>
        """.trimIndent()
        when (val result = post(Constants.AUTH_URL, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(result.data.string())
                keepUrl = data.substringAfter("<keep-url><![CDATA[").substringBefore("]]></keep-url>")
                termUrl = data.substringAfter("<term-url><![CDATA[").substringBefore("]]></term-url>")
                keepRetrySeconds = parseRetrySeconds(
                    data.substringAfter("<keep-retry>").substringBefore("</keep-retry>"),
                    RuntimeConfig.loginRetryInitialSeconds,
                )

                logger.info("Keep Url: ${sanitizeUrl(keepUrl)}")
                logger.info("Term Url: ${sanitizeUrl(termUrl)}")
                logger.info("Keep Retry: $keepRetrySeconds")
            }

            is NetResult.Error -> {
                throw IllegalStateException(result.exception)
            }
        }
    }

    private fun heartbeat(ticket: String) {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${Constants.USER_AGENT}</user-agent>
                <client-id>${States.clientId}</client-id>
                <local-time>${getTime()}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${States.userIp}</ipv4>
                <ticket>${ticket}</ticket>
                <ipv6></ipv6>
                <mac>${States.macAddress}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(keepUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(result.data.string())
                val interval = data.substringAfter("<interval>").substringBefore("</interval>")
                keepRetrySeconds = parseRetrySeconds(interval, keepRetrySeconds)
            }

            is NetResult.Error -> {
                throw IllegalStateException(result.exception)
            }
        }
    }

    fun term() {
        if (session == null || termUrl.isEmpty() || ticket.isEmpty()) return
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${Constants.USER_AGENT}</user-agent>
                <client-id>${States.clientId}</client-id>
                <local-time>${getTime()}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${States.userIp}</ipv4>
                <ticket>${ticket}</ticket>
                <ipv6></ipv6>
                <mac>${States.macAddress}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(termUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {}
            is NetResult.Error -> {
                logger.warn("SESSION_TERMINATE_FAILED ${result.exception}")
            }
        }
    }

    private fun resetSessionState(reason: String, countAbnormalRecovery: Boolean) {
        logger.warn("SESSION_RESET reason=$reason")
        try {
            session?.free()
        } catch (e: Exception) {
            logger.warn("Failed to free session: ${e.message}")
        }
        session = null
        keepUrl = ""
        termUrl = ""
        keepRetrySeconds = RuntimeConfig.loginRetryInitialSeconds
        ticket = ""
        HealthStatus.resetSession()

        if (countAbnormalRecovery) {
            abnormalRecoveries++
            if (abnormalRecoveries >= RuntimeConfig.maxAbnormalRecoveriesBeforeExit) {
                logger.error("Too many abnormal recoveries ($abnormalRecoveries), exiting so Docker can restart the container")
                HealthStatus.write()
                exitProcess(1)
            }
        }
    }

    private fun parseRetrySeconds(value: String?, fallback: Long): Long {
        return value
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(5, 600)
            ?: fallback.coerceIn(5, 600)
    }

    private fun nextLoginRetrySeconds(): Long {
        loginFailures += 1
        val delay = RuntimeConfig.loginRetryInitialSeconds * (1L shl (loginFailures - 1).coerceAtMost(5))
        return delay.coerceAtMost(RuntimeConfig.loginRetryMaxSeconds)
    }

    private fun maskSecret(value: String): String =
        if (value.length > 8) "${value.take(4)}...${value.takeLast(4)}" else "***"

    private fun sanitizeUrl(value: String): String =
        value.replace(Regex("(?i)(ticket|token|key|passwd|password)=([^&]+)"), "$1=***")
}
