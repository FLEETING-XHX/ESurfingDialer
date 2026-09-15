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

        States.useDynarmic = cmd.hasOption("dynarmic")
        DeviceIdentityStore.loadIntoStates()
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
                                States.networkStatus = networkStatus.status
                            }

                            ConnectivityStatus.IS_REDIRECTS_NOT_FOUND_IP -> {
                                HealthStatus.markError("No parameter detected in url")
                                logger.error("No parameter detected in url.")
                                if (!HealthStatus.authenticated) States.networkStatus = networkStatus.status
                            }

                            ConnectivityStatus.IS_REDIRECTS_FOUND_IP -> {
                                States.userIp = networkStatus.userIp!!
                                States.acIp = networkStatus.acIp!!
                                val now = System.currentTimeMillis() / 1000
                                HealthStatus.lastNetworkCheckAt = now
                                if (!HealthStatus.authenticated) {
                                    States.networkStatus = networkStatus.status
                                } else {
                                    val count = HealthStatus.consecutivePortalDetections.incrementAndGet()
                                    if (RecoveryPolicy.shouldReauthenticate(count,
                                            HealthStatus.consecutiveHeartbeatFailures.get(),
                                            now - HealthStatus.lastLoginSuccessAt,
                                            now - HealthStatus.lastPortalReauthAt)) {
                                        HealthStatus.lastPortalReauthAt = now
                                        States.networkStatus = networkStatus.status
                                        logger.warn("PORTAL_REAUTH_REQUESTED count=$count")
                                    } else {
                                        logger.info("PORTAL_REAUTH_DEBOUNCED count=$count")
                                    }
                                }
                            }

                            ConnectivityStatus.REQUEST_ERROR -> {
                                HealthStatus.markError(networkStatus.message)
                                logger.error("Request Error: ${networkStatus.message}")
                                if (!HealthStatus.authenticated) States.networkStatus = networkStatus.status
                            }

                            ConnectivityStatus.DEFAULT -> {
                                if (!HealthStatus.authenticated) States.networkStatus = networkStatus.status
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
                    HealthStatus.write()
                    println("Shutting down...")
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                    e.printStackTrace()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })

        clientThread.start()
        networkCheck.start()
        if (cmd.hasOption("control-stdin")) {
            kotlin.concurrent.thread(name = "windows-control", isDaemon = true) {
                while (true) {
                    val command = readlnOrNull() ?: return@thread
                    if (command == "stop") exitProcess(0)
                }
            }
        }
    }

}
