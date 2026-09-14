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

    @JvmStatic
    fun main(args: Array<String>) {
        // root directory
        if (!States.rootDir.exists()) States.rootDir.mkdirs()
        if (States.rootDir.isFile) throw IllegalArgumentException("rootDir must be directory: " + States.rootDir)
        DeviceIdentityStore.loadIntoStates()

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

        States.useDynarmic = cmd.hasOption("dynarmic")
        HealthStatus.startReporter()

        val networkCheck = object : Thread() {
            override fun run() {
                HealthStatus.networkCheckThreadAlive = true
                while (isRunning) {
                    try {
                        val networkStatus = checkConnectivity()

                        when (networkStatus.status) {
                            ConnectivityStatus.SUCCESS -> {
                                HealthStatus.markNetworkCheckSuccess()
                                States.networkStatus = ConnectivityStatus.SUCCESS
                            }

                            ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP -> {
                                HealthStatus.markError("No parameter detected in url")
                                logger.error("No parameter detected in url.")
                                if (!HealthStatus.authenticated) {
                                    States.networkStatus = networkStatus.status
                                }
                            }

                            ConnectivityStatus.IS_REDIRECTS_FOUND_IP -> {
                                States.userIp = networkStatus.userIp!!
                                States.acIp = networkStatus.acIp!!
                                HealthStatus.markNetworkCheckObserved()
                                if (HealthStatus.authenticated) {
                                    val authAge = HealthStatus.secondsSinceLastAuthSuccess()
                                    val count = HealthStatus.consecutivePortalDetections.incrementAndGet()
                                    val heartbeatFailures = HealthStatus.consecutiveHeartbeatFailures.get()
                                    if (authAge <= RuntimeConfig.postLoginPortalGraceSeconds && heartbeatFailures == 0) {
                                        logger.warn("PORTAL_DETECTED_POST_LOGIN_GRACE count=$count authAge=${authAge}s userIp=${States.userIp} acIp=${States.acIp}")
                                    } else {
                                        HealthStatus.markError("portal detected while authenticated ($count), authAge=${authAge}s")
                                        logger.warn("PORTAL_DETECTED_WHILE_AUTHENTICATED count=$count authAge=${authAge}s heartbeatFailures=$heartbeatFailures userIp=${States.userIp} acIp=${States.acIp}")
                                        val portalThresholdReached = count >= RuntimeConfig.portalDetectionThreshold
                                        val heartbeatCorroborated = heartbeatFailures > 0
                                        if (portalThresholdReached || heartbeatCorroborated) {
                                            val cooldownAge = HealthStatus.secondsSinceLastPortalReauthRequest()
                                            if (cooldownAge >= RuntimeConfig.portalReauthCooldownSeconds) {
                                                HealthStatus.markPortalReauthRequested()
                                                logger.warn("PORTAL_REAUTH_REQUESTED count=$count authAge=${authAge}s heartbeatFailures=$heartbeatFailures")
                                                States.networkStatus = networkStatus.status
                                            } else {
                                                logger.warn("PORTAL_REAUTH_SUPPRESSED_COOLDOWN count=$count cooldownAge=${cooldownAge}s")
                                            }
                                        }
                                    }
                                } else {
                                    HealthStatus.markNetworkCheckSuccess()
                                    States.networkStatus = networkStatus.status
                                }
                            }

                            ConnectivityStatus.REQUEST_ERROR -> {
                                HealthStatus.markError(networkStatus.message)
                                logger.error("Request Error: ${networkStatus.message}")
                                if (!HealthStatus.authenticated) {
                                    States.networkStatus = networkStatus.status
                                }
                            }

                            ConnectivityStatus.DEFAULT -> {
                                if (!HealthStatus.authenticated) {
                                    States.networkStatus = networkStatus.status
                                }
                            }
                        }
                        sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                    } catch (e: InterruptedException) {
                        currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        HealthStatus.markError("network monitor recovered: ${e.message}")
                        logger.error("NETWORK_MONITOR_RECOVERED", e)
                        sleep(RuntimeConfig.networkCheckIntervalSeconds * 1000)
                    }
                }
                HealthStatus.networkCheckThreadAlive = false
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
                    if (client.session != null) {
                        client.term()
                    }
                    println("Shutting down...")
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                    e.printStackTrace()
                }
            }
        })

        clientThread.start()
        networkCheck.start()
    }

}
