package com.rsplwe.esurfing

import com.rsplwe.esurfing.States.isRunning
import com.rsplwe.esurfing.States.ticket
import com.rsplwe.esurfing.hook.Session
import okhttp3.ResponseBody
import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.resetApiConnections
import com.rsplwe.esurfing.network.readAuthenticationResponse
import com.rsplwe.esurfing.network.readBoundedBody
import com.rsplwe.esurfing.utils.ConnectivityStatus.*
import com.rsplwe.esurfing.utils.NetworkConnectivityResult
import com.rsplwe.esurfing.utils.checkConnectivity
import com.rsplwe.esurfing.utils.getTime
import org.apache.log4j.Logger
import java.lang.Thread.sleep

// 交给线程异常处理器退出进程；必须先释放所有会话锁，供 shutdown hook 清理。
internal class RecoveryLimitReached : RuntimeException("recovery limit reached")

class Client(
    private val options: Options,
    private val sessionFactory: (ByteArray) -> AuthenticationSession = ::Session,
    private val send: (String, String, HashMap<String, String>) -> NetResult<ResponseBody> = ::post,
    private val probe: () -> NetworkConnectivityResult = { checkConnectivity() },
    private val monotonicNanos: () -> Long = System::nanoTime,
) : Runnable {

    private val logger: Logger = Logger.getLogger(Client::class.java)
    private val heartbeatLog = HeartbeatLog(logger)
    private var keepUrl = ""
    private var termUrl = ""
    private var keepRetrySeconds = RuntimeConfig.loginRetryInitialSeconds
    private var loginFailures = 0
    private var recoveries = 0
    private val failureReports = AuthenticationFailureReports()
    private var attemptedPortal = PortalCandidate()
    private var lastFailedPortal: PortalCandidate? = null

    @Volatile var session: AuthenticationSession? = null
        private set
    private var context: AuthenticationContext? = null
    private var sessionAlgorithm = ""
    private fun active() = context ?: throw AuthenticationFailure("SESSION_CONTEXT_MISSING", true)
    private fun request(url: String, data: String) = send(url, data, hashMapOf("Client-ID" to active().clientId, "Algo-ID" to sessionAlgorithm))

    @Volatile
    private var lastHeartbeatNanos: Long = 0
    @Volatile
    private var lastScheduledReauthLogAt: Long = 0

    override fun run() {
        HealthStatus.clientThreadAlive = true
        logger.info("APPLICATION_STARTED")
        try {
            runLoop()
        } catch (_: InterruptedException) {
            // Includes interrupts received while waiting inside an exception handler.
            Thread.currentThread().interrupt()
            logger.warn("CLIENT_THREAD_INTERRUPTED")
        } finally {
            States.cancelAuthorization()
            HealthStatus.clientThreadAlive = false
            // NIO file writes abort on an interrupted thread. Publish the final status, then restore it.
            val interrupted = Thread.interrupted()
            try { HealthStatus.write() }
            finally { if (interrupted) Thread.currentThread().interrupt() }
            logger.warn("CLIENT_THREAD_EXITED")
        }
    }

    private fun runLoop() {
        while (isRunning) {
            try {
                HealthStatus.markClientProgress("loop")
                if (States.hasPendingAuthorization()) {
                    authorization(AuthorizationTrigger.PORTAL)
                    continue
                }
                val scheduledCandidate = if (context != null) attemptedPortal else States.portalCandidate
                val hasScheduledContext = context != null || !scheduledCandidate.portalUrl.isNullOrBlank() ||
                    !scheduledCandidate.portalBody.isNullOrBlank() ||
                    (PortalConfiguration.isIpv4(scheduledCandidate.userIp) && PortalConfiguration.isIpv4(scheduledCandidate.acIp))
                if ((HealthStatus.authenticated || HealthStatus.networkReachable) && hasScheduledContext &&
                    ReauthPlanner.shouldTriggerScheduledReauth()) {
                    logger.info(
                        "SCHEDULED_REAUTH_TRIGGERED nextAt=${ReauthPlanner.formatEpoch(ReauthPlanner.snapshot().nextPlannedReauthAt)} " +
                            "window=${ReauthPlanner.safeWindowLabel()}"
                    )
                    States.requestAuthorization(scheduledCandidate, AuthorizationTrigger.SCHEDULED)
                    authorization(AuthorizationTrigger.SCHEDULED)
                    continue
                }

                if (HealthStatus.networkReachable && !hasScheduledContext && ReauthPlanner.isDue()) {
                    val now = System.currentTimeMillis() / 1000L
                    if (now - lastScheduledReauthLogAt >= 300) {
                        lastScheduledReauthLogAt = now
                        logger.info("DAILY_REAUTH_WAITING_FOR_PORTAL: 外网可达，但无本核心会话或安全门户上下文；等待门户发现，不猜测认证地址。")
                    }
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

                if (session != null && HealthStatus.authenticated) {
                    if ((monotonicNanos() - lastHeartbeatNanos) >= (keepRetrySeconds * 1_000_000_000L)) {
                        heartbeatLog.attempt()
                        try {
                            heartbeat(ticket)
                            // markHeartbeatSuccess clears the failure count; retain it for the recovery log.
                            val previousFailures = HealthStatus.consecutiveHeartbeatFailures.get()
                            HealthStatus.markHeartbeatSuccess()
                            recoveries = 0
                            heartbeatLog.success(previousFailures, keepRetrySeconds)
                        } catch (e: Exception) {
                            ReauthPlanner.recordHeartbeatFailure(e is AuthenticationFailure && e.code.startsWith("HEARTBEAT_"))
                            val failures = HealthStatus.consecutiveHeartbeatFailures.incrementAndGet()
                            HealthStatus.markError("heartbeat failed: ${e.message}")
                            logger.warn("HEARTBEAT_FAILED count=$failures: ${e.message}", e)
                            if (failures >= RuntimeConfig.heartbeatFailureThreshold) {
                                // The keep endpoint is already failing here.  A synchronous
                                // term request can consume another 30 seconds and delay the
                                // next login, so let the old session expire remotely.
                                resetSessionState("heartbeat failure threshold reached")
                                States.requestAuthorization(States.portalCandidate)
                            } else {
                                keepRetrySeconds = (failures * 5L).coerceAtMost(30)
                            }
                        }
                        lastHeartbeatNanos = monotonicNanos()
                    }
                    sleep(500)
                    continue
                }

                if (States.networkStatus == SUCCESS || States.networkStatus == DEFAULT) {
                    if (States.networkStatus == SUCCESS) HealthStatus.markClientProgress("network_online_unowned")
                    sleep(500)
                    continue
                }

                if (States.networkStatus == IS_REDIRECTS_NOT_FOUND_IP) {
                    authorization(AuthorizationTrigger.PORTAL)
                    continue
                }

                if (States.networkStatus == REQUEST_ERROR) {
                    // Keep accepted requests responsive even with a long network probe interval.
                    waitForRetry(minOf(RuntimeConfig.networkCheckIntervalSeconds, 5))
                    continue
                }

                if (States.networkStatus == IS_REDIRECTS_FOUND_IP) {
                    authorization(AuthorizationTrigger.PORTAL)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("CLIENT_THREAD_INTERRUPTED")
                break
            } catch (e: AuthenticationFailure) {
                ReauthPlanner.invalidateSample()
                HealthStatus.markError(e.code + (e.portalDiagnostics?.let { " ${it.summary()}" } ?: ""))
                resetSessionState("authorization failed", log = false)
                val delay = nextPortalRetrySeconds(attemptedPortal)
                failureReports.report(e, attemptedPortal, delay)?.let { logger.warn(it) }
                waitForRetry(delay, attemptedPortal)
            } catch (e: RecoveryLimitReached) {
                throw e
            } catch (e: Exception) {
                ReauthPlanner.invalidateSample()
                HealthStatus.markError("client loop recovered: ${e.message}")
                logger.error("CLIENT_LOOP_RECOVERED", e)
                resetSessionState("unhandled exception in client loop")
                val delay = nextLoginRetrySeconds()
                waitForRetry(delay)
            }
        }
    }

    @Synchronized private fun authorization(trigger: AuthorizationTrigger) {
        val request = States.beginAuthorization(trigger) ?: return
        attemptedPortal = request.candidate
        if (session != null) {
            val reason = when (request.trigger) {
                AuthorizationTrigger.SCHEDULED -> "scheduled reauthorization"
                AuthorizationTrigger.PORTAL -> "portal-triggered reauthorization"
                AuthorizationTrigger.INITIAL -> "initial authorization"
            }
            resetSessionState(reason, terminateRemote = true)
        } else {
            val reason = when (request.trigger) {
                AuthorizationTrigger.SCHEDULED -> "starting scheduled authorization"
                AuthorizationTrigger.PORTAL -> "starting authorization"
                AuthorizationTrigger.INITIAL -> "starting initial authorization"
            }
            clearSessionState(reason, log = false)
        }
        if (!isRunning) return
        States.algoId = "00000000-0000-0000-0000-000000000000"

        HealthStatus.markClientProgress("portal_discovery", 60)
        context = AuthenticationContext(attemptedPortal.discover(), States.clientId, States.macAddress)
        if (!isRunning) return
        sessionAlgorithm = States.algoId
        logger.info("LOGIN_ATTEMPT endpointSource=${active().endpoints.source}")
        HealthStatus.markClientProgress("native_init", 120)
        initSession()
        if (!isRunning) return
        if ((session?.getSessionId() ?: 0) == 0.toLong()) {
            HealthStatus.markError("failed to initialize session")
            logger.error("LOGIN_FAILED failed to initialize session")
            waitForRetry(nextLoginRetrySeconds())
            return
        }
        sessionAlgorithm = States.algoId
        logger.info("NATIVE_SESSION_READY")

        HealthStatus.markClientProgress("ticket", 120)
        ticket = getTicket()
        if (!isRunning) return
        logger.info("TICKET_RECEIVED")
        HealthStatus.markClientProgress("login", 120)
        login()
        if (!isRunning) return

        if (keepUrl.isEmpty()) {
            HealthStatus.markError("keepUrl is empty")
            logger.error("LOGIN_FAILED KeepUrl is empty")
            resetSessionState("empty keepUrl")
            waitForRetry(nextLoginRetrySeconds())
            return
        }

        HealthStatus.markClientProgress("confirmation", (RuntimeConfig.loginConfirmationAttempts * (RuntimeConfig.loginConfirmationIntervalSeconds + 90)).coerceAtMost(600))
        if (!confirmAuthentication()) {
            if (!isRunning) return
            HealthStatus.markError("login response was not confirmed by connectivity checks")
            logger.error("LOGIN_NOT_CONFIRMED portal remained after login response")
            resetSessionState("login not confirmed", terminateRemote = true)
            waitForRetry(nextLoginRetrySeconds())
            return
        }

        if (!States.completeAuthorization(request.version)) return
        loginFailures = 0
        lastFailedPortal = null
        failureReports.reset()
        heartbeatLog.reset()
        recoveries = 0
        lastHeartbeatNanos = monotonicNanos()
        ReauthPlanner.recordAuthSuccess(request.trigger, networkKey = ReauthPlanner.networkKey(active().endpoints.userIp, active().endpoints.acIp))
        logger.info("LOGIN_SUCCESS")
    }

    private fun initSession() {
        when (val result = request(active().endpoints.ticketUrl, sessionAlgorithm)) {
            is NetResult.Success -> {
                session = sessionFactory(readBoundedBody(result.data, "BOOTSTRAP", 1024 * 1024))
            }

            is NetResult.Error -> {
                throw AuthenticationFailure("AUTH_TRANSPORT_ERROR")
            }
        }
    }

    private fun getTicket(): String {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(active().clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(active().endpoints.userIp)}</ipv4>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(active().mac)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = request(active().endpoints.ticketUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(readAuthenticationResponse(result.data, "AUTH"))
                return AuthenticationProtocol.ticket(data)
            }

            is NetResult.Error -> {
                throw AuthenticationFailure("AUTH_TRANSPORT_ERROR")
            }
        }
    }

    private fun login() {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(active().clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <userid>${AuthenticationProtocol.escape(options.loginUser)}</userid>
                <passwd>${AuthenticationProtocol.escape(options.loginPassword)}</passwd>
            </request>
        """.trimIndent()
        when (val result = request(active().endpoints.authUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(readAuthenticationResponse(result.data, "AUTH"))
                val response = AuthenticationProtocol.login(data)
                keepUrl = response.keepUrl
                termUrl = response.termUrl
                keepRetrySeconds = response.retrySeconds
                logger.info("LOGIN_RESPONSE_VALIDATED")

                logger.info("Keep Retry: $keepRetrySeconds")
            }

            is NetResult.Error -> {
                throw AuthenticationFailure("AUTH_TRANSPORT_ERROR")
            }
        }
    }

    @Synchronized private fun heartbeat(ticket: String) {
        HealthStatus.markClientProgress("heartbeat", 120)
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(active().clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(active().endpoints.userIp)}</ipv4>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(active().mac)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = request(keepUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val data = session!!.decrypt(readAuthenticationResponse(result.data, "AUTH"))
                keepRetrySeconds = AuthenticationProtocol.heartbeat(data)
            }

            is NetResult.Error -> {
                throw AuthenticationFailure("AUTH_TRANSPORT_ERROR")
            }
        }
    }

    @Synchronized fun term() {
        if (session == null || termUrl.isEmpty() || ticket.isEmpty()) return
        HealthStatus.markClientProgress("termination", 120)
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(active().clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(active().endpoints.userIp)}</ipv4>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(active().mac)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = request(termUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> result.data.close()
            is NetResult.Error -> {
                logger.warn("SESSION_TERMINATE_FAILED AUTH_TRANSPORT_ERROR")
            }
        }
    }

    private fun confirmAuthentication(): Boolean {
        var sawPortal = false
        repeat(RuntimeConfig.loginConfirmationAttempts) { index ->
            if (!isRunning) return false
            HealthStatus.markClientProgress("confirmation_wait", RuntimeConfig.loginConfirmationIntervalSeconds + 15)
            sleep(RuntimeConfig.loginConfirmationIntervalSeconds * 1000)
            if (!isRunning) return false
            try {
                // The keep endpoint is the authoritative proof that the login
                // response was accepted.  A probe can be inconclusive behind a
                // campus proxy, so never use a successful probe as a substitute.
                heartbeat(ticket)
                logger.info("LOGIN_CONFIRMED attempt=${index + 1}")
                return true
            } catch (heartbeatError: Exception) {
                if (!isRunning) return false
                HealthStatus.markClientProgress("confirmation_probe", RuntimeConfig.networkProbeBudgetSeconds + 15)
                val result = probe()
                ReauthPlanner.observeNetwork(result)
                when (result.status) {
                    IS_REDIRECTS_FOUND_IP -> {
                        sawPortal = true
                        States.requestInitialAuthorization(rememberPortal(result))
                        logger.warn("LOGIN_CONFIRMATION_PORTAL attempt=${index + 1} userIp=${States.userIp} acIp=${States.acIp} heartbeat=${heartbeatError.message}")
                    }

                    IS_REDIRECTS_NOT_FOUND_IP -> {
                        States.requestInitialAuthorization(rememberPortal(result))
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

    @Synchronized private fun resetSessionState(reason: String, terminateRemote: Boolean = false, log: Boolean = true) {
        val hadSession = session != null || HealthStatus.authenticated
        if (terminateRemote && session != null) {
            try {
                term()
            } catch (e: Exception) {
                logger.warn("SESSION_TERMINATE_RECOVERY_FAILED ${e.message}")
            }
        }
        clearSessionState(reason, log)
        if (!hadSession) return
        recoveries++
        if (recoveries >= RuntimeConfig.maxRecoveriesBeforeExit) {
            logger.error("Too many recoveries ($recoveries), exiting so Docker can restart the container")
            HealthStatus.write()
            throw RecoveryLimitReached()
        }
    }

    @Synchronized private fun clearSessionState(reason: String, log: Boolean = true) {
        if (log) logger.warn("SESSION_RESET reason=$reason")
        HealthStatus.markClientProgress("native_cleanup", 120)
        try {
            session?.free()
        } catch (e: Exception) {
            logger.warn("Failed to free session: ${e.message}")
        }
        session = null
        context = null
        sessionAlgorithm = ""
        keepUrl = ""
        termUrl = ""
        keepRetrySeconds = RuntimeConfig.loginRetryInitialSeconds
        heartbeatLog.reset()
        ticket = ""
        HealthStatus.resetSession()
        resetApiConnections()
    }

    @Synchronized fun shutdown() {
        States.cancelAuthorization()
        try { term() } finally { clearSessionState("shutdown") }
    }

    private fun waitForRetry(seconds: Long, failedPortal: PortalCandidate? = null) {
        var remaining = seconds
        while (remaining > 0 && isRunning) {
            if (failedPortal != null && !failedPortal.sameRetryTarget(States.retryCandidate())) return
            val chunk = minOf(remaining, 5)
            HealthStatus.markClientProgress("retry_wait", chunk + 15)
            sleep(chunk * 1000)
            remaining -= chunk
        }
    }

    private fun nextPortalRetrySeconds(candidate: PortalCandidate): Long {
        if (!candidate.sameRetryTarget(lastFailedPortal)) loginFailures = 0
        lastFailedPortal = candidate
        return nextLoginRetrySeconds()
    }

    private fun nextLoginRetrySeconds(): Long {
        loginFailures += 1
        val delay = RuntimeConfig.loginRetryInitialSeconds * (1L shl (loginFailures - 1).coerceAtMost(5))
        return delay.coerceAtMost(RuntimeConfig.loginRetryMaxSeconds)
    }

}
