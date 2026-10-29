package com.rsplwe.esurfing

import com.rsplwe.esurfing.States.isRunning
import com.rsplwe.esurfing.utils.ConnectivityStatus
import com.rsplwe.esurfing.utils.checkConnectivity
import org.apache.commons.cli.*
import org.apache.commons.cli.Options
import org.apache.log4j.Logger
import kotlin.system.exitProcess

object DialerApp {

    private val logger: Logger = Logger.getLogger(DialerApp::class.java)
    private var stateOwnership: CoreStateOwnership? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options()
        val loginUser = Option.builder("u").longOpt("user")
            .argName("user")
            .hasArg()
            .required(true)
            .desc("Login User (Phone Number or Other)").build()
        val loginPassword = Option.builder("p").longOpt("password")
            .argName("password")
            .hasArg()
            .required(true)
            .desc("Login User Password").build()
        val useDynarmicBackend = Option.builder("d").longOpt("dynarmic")
            .argName("dynarmic")
            .hasArg(false)
            .required(false)
            .desc("Use Dynarmic Backend").build()

        options.addOption(loginUser)
        options.addOption(loginPassword)
        options.addOption(useDynarmicBackend)
        options.addOption(Option.builder().longOpt("control-stdin").desc("Allow the Windows supervisor to request a clean shutdown").build())

        val cmd: CommandLine
        val parser: CommandLineParser = DefaultParser()
        val helper = HelpFormatter()

        try {
            cmd = parser.parse(options, args)
        } catch (e: ParseException) {
            logger.error(e.message)
            helper.printHelp("ESurfingDialer", options)
            exitProcess(1)
        }

        // root directory
        if (!States.rootDir.exists()) States.rootDir.mkdirs()
        if (States.rootDir.isFile) throw IllegalArgumentException("rootDir must be directory: " + States.rootDir)
        val ownership = CoreStateOwnership.acquire(States.rootDir)
        if (ownership == null) {
            System.err.println("CORE_STATE_IN_USE")
            exitProcess(73)
        }
        stateOwnership = ownership
        try { DailyReauthPlanner.snapshot() }
        catch (error: IllegalArgumentException) {
            ownership.close()
            System.err.println("DAILY_REAUTH_TIME_INVALID: 结束时间不能早于起始时间")
            exitProcess(1)
        }

        States.useDynarmic = cmd.hasOption("dynarmic")
        DeviceIdentityStore.loadIntoStates()
        HealthStatus.startReporter()
        val repeatedLogLimiter = LogRateLimiter()
        val portalRecovery = PortalRecoveryTracker()
        fun requestPortalRecovery(result: com.rsplwe.esurfing.utils.NetworkConnectivityResult) {
            val now = System.currentTimeMillis() / 1000
            if (portalRecovery.observe(result, States.userIp, States.acIp,
                    HealthStatus.consecutiveHeartbeatFailures.get(), now - HealthStatus.lastLoginSuccessAt,
                    now - HealthStatus.lastPortalReauthAt)) {
                // Keep the accepted recovery pending even if a later connectivity probe returns SUCCESS.
                HealthStatus.lastPortalReauthAt = now
                States.requestAuthorization(result)
                logger.warn("PORTAL_REAUTH_REQUESTED")
                repeatedLogLimiter.reset("portal-debounced")
            } else if (repeatedLogLimiter.shouldLog("portal-debounced")) {
                logger.info("PORTAL_REAUTH_DEBOUNCED")
            }
        }

        val networkCheck = object : Thread() {
            override fun run() {
                HealthStatus.updateNetworkCheckThreadAlive(true)
                while (isRunning) {
                    try {
                        val networkStatus = checkConnectivity()
                        val suspicious = networkStatus.status != ConnectivityStatus.SUCCESS
                        if (suspicious) HealthStatus.markNetworkCheckNotReachable()

                        when (networkStatus.status) {
                            ConnectivityStatus.SUCCESS -> {
                                portalRecovery.reset()
                                HealthStatus.markNetworkCheckSuccess()
                                States.updateNetworkStatus(networkStatus.status)
                                repeatedLogLimiter.reset("network-error")
                                repeatedLogLimiter.reset("portal-missing-parameters")
                                repeatedLogLimiter.reset("portal-debounced")
                            }

                            ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP -> {
                                // The first redirect need not contain IPs; retain it for bounded portal discovery.
                                States.observePortal(networkStatus)
                                HealthStatus.markError(networkStatus.message.takeUnless { it == "ok" } ?: "Portal requires configuration discovery")
                                if (repeatedLogLimiter.shouldLog("portal-missing-parameters"))
                                    logger.error("No parameter detected in url.")
                                if (!HealthStatus.authenticated) {
                                    portalRecovery.reset()
                                    States.updateNetworkStatus(networkStatus.status)
                                } else requestPortalRecovery(networkStatus)
                            }

                            ConnectivityStatus.IS_REDIRECTS_FOUND_IP -> {
                                States.observePortal(networkStatus)
                                val now = System.currentTimeMillis() / 1000
                                HealthStatus.lastNetworkCheckAt = now
                                if (!HealthStatus.authenticated) {
                                    portalRecovery.reset()
                                    States.updateNetworkStatus(networkStatus.status)
                                } else {
                                    requestPortalRecovery(networkStatus)
                                }
                            }

                            ConnectivityStatus.REQUEST_ERROR -> {
                                portalRecovery.reset()
                                HealthStatus.markError(networkStatus.message)
                                if (repeatedLogLimiter.shouldLog("network-error"))
                                    logger.error("Request Error: ${networkStatus.message}")
                                if (!HealthStatus.authenticated) States.updateNetworkStatus(networkStatus.status)
                            }

                            ConnectivityStatus.DEFAULT -> {
                                portalRecovery.reset()
                                if (!HealthStatus.authenticated) States.updateNetworkStatus(networkStatus.status)
                            }
                        }
                        CoreSignals.waitForNetworkMonitor(RuntimeConfig.networkCheckDelaySeconds(suspicious) * 1000)
                    } catch (e: InterruptedException) {
                        currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        HealthStatus.markNetworkCheckNotReachable()
                        HealthStatus.markError("network monitor recovered: ${e.message}")
                        if (repeatedLogLimiter.shouldLog("network-monitor-exception"))
                            logger.error("NETWORK_MONITOR_RECOVERED", e)
                        CoreSignals.waitForNetworkMonitor(RuntimeConfig.networkCheckDelaySeconds(suspicious = true) * 1000)
                    }
                }
                HealthStatus.updateNetworkCheckThreadAlive(false)
            }
        }

        val client = Client(Options(cmd.getOptionValue("user"), cmd.getOptionValue("password")))
        val clientThread = Thread(client, "dialer-client")
        clientThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
            logger.error("Critical thread ${thread.name} crashed", error)
            HealthStatus.markError("critical thread crashed: ${error.message}")
            HealthStatus.write()
            exitProcess(1)
        }
        networkCheck.name = "network-monitor"
        networkCheck.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
            logger.error("Critical thread ${thread.name} crashed", error)
            HealthStatus.markError("critical thread crashed: ${error.message}")
            HealthStatus.write()
            exitProcess(1)
        }

        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    if (isRunning) {
                        isRunning = false
                    }
                    States.cancelAuthorization()
                    CoreSignals.wakeNetworkMonitor()
                    if (client.session != null) {
                        client.term()
                    }
                    HealthStatus.write()
                    println("Shutting down...")
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                    e.printStackTrace()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // stateOwnership is retained through terminal reporter writes; the OS releases it at process exit.
            }
        })

        System.getenv("CORE_PARENT_PID")?.toLongOrNull()?.takeIf { it > 0 }?.let { pid ->
            CoreParentMonitor.watch(pid) { exitProcess(0) }
        }

        clientThread.start()
        networkCheck.start()
        if (cmd.hasOption("control-stdin")) {
            kotlin.concurrent.thread(name = "windows-control", isDaemon = true) {
                WindowsControl.run(::readlnOrNull, { exitProcess(0) }) { command ->
                    when (command) {
                        "enhanced on", "enhanced=1", "enhanced true" -> RuntimeConfig.setEnhancedConnection(true)
                        "enhanced off", "enhanced=0", "enhanced false" -> RuntimeConfig.setEnhancedConnection(false)
                        "network recheck" -> CoreSignals.requestNetworkRecheck()
                        else -> {
                            val fields = command.split(Regex("\\s+"))
                            if (fields.size == 4 && fields[0] == "reauth" && fields[1] in setOf("on", "off")) {
                                try {
                                    DailyReauthPlanner.configure(fields[1] == "on", fields[2].toInt(), fields[3].toInt())
                                } catch (_: IllegalArgumentException) { logger.warn("DAILY_REAUTH_TIME_INVALID") }
                            }
                        }
                    }
                }
            }
        }
    }

}
