package com.rsplwe.esurfing

import com.rsplwe.esurfing.States.isRunning
import com.rsplwe.esurfing.States.ticket
import com.rsplwe.esurfing.hook.Session
import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.resetApiConnections
import com.rsplwe.esurfing.utils.ConnectivityStatus.*
import com.rsplwe.esurfing.utils.checkConnectivity
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
    private var recoveries = 0

    var session: Session? = null

    @Volatile
    var tick: Long = 0
    @Volatile
    private var lastScheduledReauthLogAt: Long = 0

    override fun run() {
        HealthStatus.clientThreadAlive = true
        logger.info("APPLICATION_STARTED")
        while (isRunning) {
            try {
                if (session != null && HealthStatus.authenticated && ReauthPlanner.shouldTriggerScheduledReauth()) {
                    logger.info(
                        "SCHEDULED_REAUTH_TRIGGERED nextAt=${ReauthPlanner.formatEpoch(ReauthPlanner.snapshot().nextPlannedReauthAt)} " +
                            "window=${ReauthPlanner.safeWindowLabel()}"
                    )
                    authorization(AuthorizationTrigger.SCHEDULED)
                    continue
                }

                if (States.networkStatus == DEFAULT) {
                    sleep(1000)
                    continue
                }

                if (session != null && HealthStatus.authenticated && ReauthPlanner.shouldTriggerScheduledReauth()) {
                    logger.info(
                        "SCHEDULED_REAUTH_TRIGGERED nextAt=${ReauthPlanner.formatEpoch(ReauthPlanner.snapshot().nextPlannedReauthAt)} " +
                            "window=${ReauthPlanner.safeWindowLabel()}"
                    )
                    authorization(AuthorizationTrigger.SCHEDULED)
                    continue
                }

                if (session != null && HealthStatus.authenticated && ReauthPlanner.shouldLogWaiting()) {
                    val now = System.currentTimeMillis() / 1000L
                    if (now - lastScheduledReauthLogAt >= 300) {
                        lastScheduledReauthLogAt = now
                        logger.info(
                            "SCHEDULED_REAUTH_WAITING nextAt=${ReauthPlanner.formatEpoch(ReauthPlanner.snapshot().nextPlannedReauthAt)} " +
                                "window=${ReauthPlanner.safeWindowLabel()}"
                        )
                    }
                }

                if (session != null && HealthStatus.authenticated && States.networkStatus != IS_REDIRECTS_FOUND_IP) {
                    if ((System.currentTimeMillis() - tick) >= (keepRetrySeconds * 1000)) {
                        logger.info("HEARTBEAT_ATTEMPT")
                        try {
                            heartbeat(ticket)
                            HealthStatus.markHeartbeatSuccess()
                            recoveries = 0
                            logger.info("HEARTBEAT_SUCCESS nextRetry=${keepRetrySeconds}s")
                        } catch (e: Exception) {
                            val failures = HealthStatus.consecutiveHeartbeatFailures.incrementAndGet()
                            HealthStatus.markError("heartbeat failed: ${e.message}")
                            logger.warn("HEARTBEAT_FAILED count=$failures: ${e.message}", e)
                            if (failures >= RuntimeConfig.heartbeatFailureThreshold) {
                                // The keep endpoint is already failing here.  A synchronous
                                // term request can consume another 30 seconds and delay the
                                // next login, so let the old session expire remotely.
                                resetSessionState("heartbeat failure threshold reached")
                                States.networkStatus = DEFAULT
                            } else {
                                keepRetrySeconds = (failures * 5L).coerceAtMost(30)
                            }
                        }
                        tick = System.currentTimeMillis()
                    }
                    sleep(500)
                    continue
                }

                if (States.networkStatus == SUCCESS) {
                    sleep(500)
                    continue
                }

                if (States.networkStatus == IS_REDIRECTS_NOT_FOUND_IP) {
                    HealthStatus.markError("portal redirect missing user/ac ip")
                    sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                    continue
                }

                if (States.networkStatus == REQUEST_ERROR) {
                    sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                    continue
                }

                if (States.networkStatus == IS_REDIRECTS_FOUND_IP) {
                    authorization(AuthorizationTrigger.PORTAL)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("CLIENT_THREAD_INTERRUPTED")
                break
            } catch (e: Exception) {
                HealthStatus.markError("client loop recovered: ${e.message}")
                logger.error("CLIENT_LOOP_RECOVERED", e)
                resetSessionState("unhandled exception in client loop")
                val delay = nextLoginRetrySeconds()
                sleep(delay * 1000)
            }
        }
        HealthStatus.clientThreadAlive = false
        HealthStatus.write()
        logger.warn("CLIENT_THREAD_EXITED")
    }

    private fun authorization(trigger: AuthorizationTrigger) {
        if (session != null) {
            val reason = when (trigger) {
                AuthorizationTrigger.SCHEDULED -> "scheduled reauthorization"
                AuthorizationTrigger.PORTAL -> "portal-triggered reauthorization"
                AuthorizationTrigger.INITIAL -> "initial authorization"
            }
            resetSessionState(reason, terminateRemote = true)
        } else {
            val reason = when (trigger) {
                AuthorizationTrigger.SCHEDULED -> "starting scheduled authorization"
                AuthorizationTrigger.PORTAL -> "starting authorization"
                AuthorizationTrigger.INITIAL -> "starting initial authorization"
            }
            clearSessionState(reason)
        }
        States.algoId = "00000000-0000-0000-0000-000000000000"

        logger.info("LOGIN_ATTEMPT userIp=${States.userIp} acIp=${States.acIp}")
        initSession()
        if ((session?.getSessionId() ?: 0) == 0.toLong()) {
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
            resetSessionState("empty keepUrl")
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }

        if (!confirmAuthentication()) {
            HealthStatus.markError("login response was not confirmed by connectivity checks")
            logger.error("LOGIN_NOT_CONFIRMED portal remained after login response")
            resetSessionState("login not confirmed", terminateRemote = true)
            States.networkStatus = DEFAULT
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }

        States.networkStatus = SUCCESS
        loginFailures = 0
        recoveries = 0
        tick = System.currentTimeMillis()
        ReauthPlanner.recordAuthSuccess(trigger)
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
                return data.substringAfter("<ticket>").substringBefore("</ticket>")
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
                logger.debug(data)
                keepUrl = data.substringAfter("<keep-url><![CDATA[").substringBefore("]]></keep-url>")
                termUrl = data.substringAfter("<term-url><![CDATA[").substringBefore("]]></term-url>")
                keepRetrySeconds = parseRetrySeconds(
                    data.substringAfter("<keep-retry>").substringBefore("</keep-retry>"),
                    RuntimeConfig.loginRetryInitialSeconds
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
                keepRetrySeconds = parseRetrySeconds(
                    data.substringAfter("<interval>").substringBefore("</interval>"),
                    keepRetrySeconds
                )
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
            is NetResult.Success -> result.data.close()
            is NetResult.Error -> {
                logger.warn("SESSION_TERMINATE_FAILED ${result.exception.message}")
            }
        }
    }

    private fun confirmAuthentication(): Boolean {
        var sawPortal = false
        repeat(RuntimeConfig.loginConfirmationAttempts) { index ->
            sleep(RuntimeConfig.loginConfirmationIntervalSeconds * 1000)
            try {
                // The keep endpoint is the authoritative proof that the login
                // response was accepted.  A probe can be inconclusive behind a
                // campus proxy, so never use a successful probe as a substitute.
                heartbeat(ticket)
                logger.info("LOGIN_CONFIRMED attempt=${index + 1}")
                return true
            } catch (heartbeatError: Exception) {
                val result = checkConnectivity()
                when (result.status) {
                    IS_REDIRECTS_FOUND_IP -> {
                        sawPortal = true
                        States.userIp = result.userIp.orEmpty()
                        States.acIp = result.acIp.orEmpty()
                        logger.warn("LOGIN_CONFIRMATION_PORTAL attempt=${index + 1} userIp=${States.userIp} acIp=${States.acIp} heartbeat=${heartbeatError.message}")
                    }

                    IS_REDIRECTS_NOT_FOUND_IP -> {
                        sawPortal = true
                        logger.warn("LOGIN_CONFIRMATION_PORTAL_MISSING_IP attempt=${index + 1} heartbeat=${heartbeatError.message}")
                    }

                    SUCCESS, REQUEST_ERROR, DEFAULT -> {
                        logger.warn("LOGIN_CONFIRMATION_FAILED attempt=${index + 1} status=${result.status} message=${result.message} heartbeat=${heartbeatError.message}")
                    }
                }
            }
        }
        logger.warn("LOGIN_NOT_CONFIRMED portalDetected=$sawPortal")
        return false
    }

    private fun resetSessionState(reason: String, terminateRemote: Boolean = false) {
        val hadSession = session != null || HealthStatus.authenticated
        if (terminateRemote && session != null) {
            try {
                term()
            } catch (e: Exception) {
                logger.warn("SESSION_TERMINATE_RECOVERY_FAILED ${e.message}")
            }
        }
        clearSessionState(reason)
        if (!hadSession) return
        recoveries++
        if (recoveries >= RuntimeConfig.maxRecoveriesBeforeExit) {
            logger.error("Too many recoveries ($recoveries), exiting so Docker can restart the container")
            HealthStatus.write()
            exitProcess(1)
        }
    }

    private fun clearSessionState(reason: String) {
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
        resetApiConnections()
    }

    private fun parseRetrySeconds(value: String?, fallback: Long): Long {
        val parsed = value
            ?.trim()
            ?.toLongOrNull()
            ?: fallback
        return parsed.coerceIn(5, RuntimeConfig.heartbeatIntervalMaxSeconds)
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
