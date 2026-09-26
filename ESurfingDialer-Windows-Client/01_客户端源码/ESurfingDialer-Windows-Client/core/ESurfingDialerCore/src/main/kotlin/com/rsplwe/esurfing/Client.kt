package com.rsplwe.esurfing

import com.rsplwe.esurfing.States.isRunning
import com.rsplwe.esurfing.States.ticket
import com.rsplwe.esurfing.hook.Session
import com.rsplwe.esurfing.network.NetResult
import com.rsplwe.esurfing.network.post
import com.rsplwe.esurfing.network.resetApiConnections
import com.rsplwe.esurfing.network.requestSessionBootstrap
import com.rsplwe.esurfing.network.readAuthenticationResponse
import com.rsplwe.esurfing.utils.checkConnectivity
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
    private val authenticationRetryPolicy = AuthenticationRetryPolicy()
    private var endpoints: AuthenticationEndpoints? = null
    private var attemptedPortal: com.rsplwe.esurfing.utils.NetworkConnectivityResult? = null

    var session: Session? = null

    @Volatile
    var tick: Long = 0

    override fun run() {
        HealthStatus.updateClientThreadAlive(true)
        logger.info("APPLICATION_STARTED")
        try {
            while (isRunning) {
                try {
                    runClientIteration()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.warn("CLIENT_THREAD_INTERRUPTED")
                    break
                } catch (e: AuthenticationFailure) {
                    val blocked = authenticationRetryPolicy.record(e)
                    resetSessionState("authentication failed: ${e.code}", countAbnormalRecovery = false)
                    HealthStatus.markAuthenticationFailure(e, blocked)
                    logger.error("LOGIN_FAILED code=${e.code} retry=${if (blocked) "paused" else "backoff"}")
                    if (!blocked) CoreSignals.waitForClient(nextLoginRetrySeconds() * 1000)
                } catch (e: Exception) {
                    HealthStatus.markError("client loop recovered: ${e.message}")
                    logger.error("CLIENT_LOOP_RECOVERED", e)
                    resetSessionState("unhandled exception in client loop", countAbnormalRecovery = true)
                    sleep(nextLoginRetrySeconds() * 1000)
                }
            }
        } finally {
            HealthStatus.updateClientThreadAlive(false)
            HealthStatus.write()
            logger.warn("CLIENT_THREAD_EXITED")
        }
    }

    private fun runClientIteration() {
        if (HealthStatus.authenticationBlocked) {
            if (States.portal != null && portalIdentity(States.portal) != portalIdentity(attemptedPortal)) {
                authenticationRetryPolicy.reset()
                HealthStatus.clearAuthenticationFailure()
            } else {
                CoreSignals.waitForClient(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                return
            }
        }
        if (session != null && HealthStatus.authenticated
            && !States.forceAuthorization && States.networkStatus != IS_REDIRECTS_FOUND_IP) {
            waitForHeartbeatOrRecovery()
            return
        }
        when {
            States.networkStatus == DEFAULT -> {
                CoreSignals.waitForClient(1000)
            }

            States.networkStatus == REQUEST_ERROR -> {
                CoreSignals.waitForClient(RuntimeConfig.networkCheckIntervalSeconds * 1000)
            }

            States.networkStatus == IS_REDIRECTS_NOT_FOUND_IP && States.portal?.portalUrl == null
                && States.portal?.portalBody == null -> {
                HealthStatus.markError("portal redirect missing user/ac ip")
                CoreSignals.waitForClient(RuntimeConfig.networkCheckIntervalSeconds * 1000)
            }

            States.forceAuthorization || States.networkStatus == IS_REDIRECTS_FOUND_IP
                || States.networkStatus == IS_REDIRECTS_NOT_FOUND_IP -> {
                authorization()
            }

            States.networkStatus == SUCCESS -> {
                if (session != null && HealthStatus.authenticated) {
                    waitForHeartbeatOrRecovery()
                } else {
                    CoreSignals.waitForClient(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                }
            }
        }
    }

    private fun waitForHeartbeatOrRecovery() {
        val remaining = keepRetrySeconds * 1000 - (System.currentTimeMillis() - tick)
        if (remaining <= 0) maybeHeartbeat()
        else CoreSignals.waitForClient(remaining)
    }

    private fun maybeHeartbeat() {
        if ((System.currentTimeMillis() - tick) < keepRetrySeconds * 1000) return

        logger.info("HEARTBEAT_ATTEMPT")
        try {
            heartbeat(ticket)
            HealthStatus.markHeartbeatSuccess()
            abnormalRecoveries = 0
            logger.info("HEARTBEAT_SUCCESS nextRetry=${keepRetrySeconds}s")
        } catch (e: Exception) {
            val failures = HealthStatus.consecutiveHeartbeatFailures.incrementAndGet()
            HealthStatus.markError("heartbeat failed: ${e.message}")
            logger.warn("HEARTBEAT_FAILED count=$failures: ${e.message}", e)

            if (failures >= RuntimeConfig.heartbeatFailureThreshold) {
                resetSessionState("heartbeat failure threshold reached", countAbnormalRecovery = true)
                States.forceAuthorization = true
                States.updateNetworkStatus(DEFAULT)
            } else {
                keepRetrySeconds = (failures * 5L).coerceAtMost(30)
            }
        } finally {
            tick = System.currentTimeMillis()
        }
    }

    private fun authorization() {
        if (session != null && HealthStatus.authenticated) {
            try { term() } catch (e: Exception) { logger.warn("SESSION_TERMINATE_RECOVERY_FAILED", e) }
        }
        resetSessionState("starting authorization", countAbnormalRecovery = false)
        HealthStatus.clearAuthenticationFailure()
        States.algoId = "00000000-0000-0000-0000-000000000000"

        logger.info("LOGIN_ATTEMPT channel=android64_native ua=${Constants.USER_AGENT}")
        attemptedPortal = States.portal
        HealthStatus.beginAuthenticationStage("portal")
        val portal = attemptedPortal
        endpoints = PortalConfiguration.discover(portal?.portalUrl, portal?.portalBody,
            portal?.userIp ?: States.userIp, portal?.acIp ?: States.acIp)
        States.userIp = endpoints!!.userIp
        States.acIp = endpoints!!.acIp
        logger.info("AUTH_ENDPOINTS source=${endpoints!!.source} auth=${AuthenticationDiagnostics.endpoint(endpoints!!.authUrl)} ticket=${AuthenticationDiagnostics.endpoint(endpoints!!.ticketUrl)}")
        HealthStatus.beginAuthenticationStage("session")
        initSession()

        logger.info("SESSION_READY")
        HealthStatus.beginAuthenticationStage("ticket")
        ticket = getTicket()
        logger.info("TICKET_READY")
        HealthStatus.beginAuthenticationStage("login")
        login()

        if (keepUrl.isEmpty()) {
            HealthStatus.markError("keepUrl is empty")
            logger.error("LOGIN_FAILED KeepUrl is empty")
            resetSessionState("empty keepUrl", countAbnormalRecovery = true)
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }

        HealthStatus.beginAuthenticationStage("confirm")
        if (!confirmAuthentication()) {
            HealthStatus.markError("login response was not confirmed by keep heartbeat")
            logger.warn("LOGIN_NOT_CONFIRMED")
            resetSessionState("login not confirmed", countAbnormalRecovery = true)
            States.updateNetworkStatus(DEFAULT)
            sleep(nextLoginRetrySeconds() * 1000)
            return
        }
        States.forceAuthorization = false
        States.updateNetworkStatus(SUCCESS)
        authenticationRetryPolicy.reset()
        loginFailures = 0
        abnormalRecoveries = 0
        tick = System.currentTimeMillis()
        HealthStatus.markLoginSuccess()
        logger.info("LOGIN_SUCCESS")
    }

    private fun initSession() {
        val zsm = requestSessionBootstrap(endpoints!!.ticketUrl, States.algoId)
        val metadata = AuthenticationDiagnostics.inspect(zsm)
        logger.info("ZSM_METADATA format=${metadata.format} algoIdCandidate=${metadata.algoId ?: "unavailable"}")
        try { session = Session(zsm) }
        catch (e: AuthenticationFailure) { throw e }
        catch (_: Exception) { throw AuthenticationFailure("NATIVE_ENVIRONMENT_INIT_FAILED", true) }
    }

    private fun decryptedResponse(result: NetResult.Success<okhttp3.ResponseBody>, stage: String): String {
        val encrypted = readAuthenticationResponse(result.data, stage)
        return try { session!!.decrypt(encrypted) }
        catch (_: Exception) { throw AuthenticationFailure("${stage}_DECRYPT_FAILED", true) }
    }

    private fun getTicket(): String {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(States.clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(endpoints!!.userIp)}</ipv4>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(States.macAddress)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(endpoints!!.ticketUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                return AuthenticationProtocol.ticket(decryptedResponse(result, "TICKET"))
            }

            is NetResult.Error -> {
                throw AuthenticationFailure(if (result.exception.startsWith("HTTP")) "TICKET_HTTP_ERROR" else "TICKET_TRANSPORT_ERROR")
            }
        }
    }

    private fun login() {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(States.clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <userid>${AuthenticationProtocol.escape(options.loginUser)}</userid>
                <passwd>${AuthenticationProtocol.escape(options.loginPassword)}</passwd>
            </request>
        """.trimIndent()
        when (val result = post(endpoints!!.authUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                val response = AuthenticationProtocol.login(decryptedResponse(result, "LOGIN"))
                keepUrl = response.keepUrl
                termUrl = response.termUrl
                keepRetrySeconds = response.retrySeconds

                logger.info("Keep endpoint: ${AuthenticationDiagnostics.endpoint(keepUrl)}")
                logger.info("Term endpoint: ${AuthenticationDiagnostics.endpoint(termUrl)}")
                logger.info("Keep Retry: $keepRetrySeconds")
            }

            is NetResult.Error -> {
                throw AuthenticationFailure(if (result.exception.startsWith("HTTP")) "LOGIN_HTTP_ERROR" else "LOGIN_TRANSPORT_ERROR")
            }
        }
    }

    private fun heartbeat(ticket: String) {
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(States.clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(endpoints!!.userIp)}</ipv4>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(States.macAddress)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(keepUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> {
                keepRetrySeconds = AuthenticationProtocol.heartbeat(decryptedResponse(result, "HEARTBEAT"))
            }

            is NetResult.Error -> {
                throw AuthenticationFailure(if (result.exception.startsWith("HTTP")) "HEARTBEAT_HTTP_ERROR" else "HEARTBEAT_TRANSPORT_ERROR")
            }
        }
    }

    fun term() {
        if (session == null || termUrl.isEmpty() || ticket.isEmpty()) return
        val payload = """
            <?xml version="1.0" encoding="utf-8"?>
            <request>
                <user-agent>${AuthenticationProtocol.escape(Constants.USER_AGENT)}</user-agent>
                <client-id>${AuthenticationProtocol.escape(States.clientId)}</client-id>
                <local-time>${AuthenticationProtocol.escape(getTime())}</local-time>
                <host-name>Xiaomi 6</host-name>
                <ipv4>${AuthenticationProtocol.escape(endpoints!!.userIp)}</ipv4>
                <ticket>${AuthenticationProtocol.escape(ticket)}</ticket>
                <ipv6></ipv6>
                <mac>${AuthenticationProtocol.escape(States.macAddress)}</mac>
                <ostag>Xiaomi 6</ostag>
            </request>
        """.trimIndent()
        when (val result = post(termUrl, session!!.encrypt(payload))) {
            is NetResult.Success -> result.data.close()
            is NetResult.Error -> {
                logger.warn("SESSION_TERMINATE_FAILED ${result.exception}")
            }
        }
    }

    private fun confirmAuthentication(): Boolean {
        repeat(RuntimeConfig.loginConfirmationAttempts) { attempt ->
            HealthStatus.beginAuthenticationStage("confirm")
            sleep(RuntimeConfig.loginConfirmationIntervalSeconds * 1000)
            try {
                heartbeat(ticket)
                logger.info("LOGIN_CONFIRMED attempt=${attempt + 1}")
                return true
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (e is AuthenticationFailure && e.deterministic) throw e
                val probe = checkConnectivity()
                if (probe.status == IS_REDIRECTS_FOUND_IP || probe.portalUrl != null || probe.portalBody != null) {
                    // A newly discovered portal is for the next attempt, never mutate this session's IPs.
                    States.portal = probe
                }
                logger.warn("LOGIN_CONFIRMATION_FAILED attempt=${attempt + 1} status=${probe.status}", e)
            }
        }
        return false
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
        resetApiConnections()

        if (countAbnormalRecovery) {
            abnormalRecoveries++
            if (abnormalRecoveries >= RuntimeConfig.maxAbnormalRecoveriesBeforeExit) {
                logger.error("Too many abnormal recoveries ($abnormalRecoveries), exiting for the Windows supervisor")
                HealthStatus.write()
                exitProcess(1)
            }
        }
    }

    private fun nextLoginRetrySeconds(): Long {
        loginFailures += 1
        val delay = RuntimeConfig.loginRetryInitialSeconds * (1L shl (loginFailures - 1).coerceAtMost(5))
        return delay.coerceAtMost(RuntimeConfig.loginRetryMaxSeconds)
    }

    private fun portalIdentity(portal: com.rsplwe.esurfing.utils.NetworkConnectivityResult?): String =
        "${portal?.userIp}|${portal?.acIp}|${AuthenticationDiagnostics.endpoint(portal?.portalUrl.orEmpty())}"

}
