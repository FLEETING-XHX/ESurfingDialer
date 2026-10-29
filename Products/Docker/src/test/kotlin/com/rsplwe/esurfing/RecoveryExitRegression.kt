package com.rsplwe.esurfing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** 真正子 JVM 验证嵌套会话锁、异常处理器和 shutdown hook，不加载原生库或联网。 */
object RecoveryExitRegression {
    @JvmStatic fun main(args: Array<String>) {
        if (args.isNotEmpty()) {
            val marker = Path.of(args[0])
            var frees = 0
            val client = Client(Options("synthetic", "synthetic"),
                sessionFactory = { error("native loading forbidden") },
                send = { _, _, _ -> error("network forbidden") },
                probe = { error("network forbidden") })
            Client::class.java.getDeclaredField("session").apply { isAccessible = true }.set(client,
                object : AuthenticationSession {
                    override fun encrypt(value: String) = value
                    override fun decrypt(value: String) = value
                    override fun getSessionId() = 1L
                    override fun free() { frees++ }
                })
            Client::class.java.getDeclaredField("recoveries").apply { isAccessible = true }
                .setInt(client, RuntimeConfig.maxRecoveriesBeforeExit - 1)
            States.isRunning = true
            States.requestAuthorization(PortalCandidate())
            Runtime.getRuntime().addShutdownHook(Thread {
                States.isRunning = false
                client.shutdown()
                check(frees == 1 && client.session == null && !HealthStatus.clientThreadAlive)
                Files.writeString(marker, "shutdown-completed")
            })
            val worker = Thread(client, "recovery-limit")
            worker.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                check(error is RecoveryLimitReached && !Thread.holdsLock(client))
                exitProcess(1)
            }
            worker.start()
            worker.join()
            error("recovery limit did not terminate the JVM")
        }
        val directory = Files.createTempDirectory("recovery-exit-")
        val marker = directory.resolve("shutdown.txt")
        val child = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), javaClass.name, marker.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile())
        child.environment()["STATE_DIR"] = directory.resolve("state").toString()
        val process = child.start()
        try {
            check(process.waitFor(12, TimeUnit.SECONDS)) { "shutdown hook deadlocked: $directory" }
            check(process.exitValue() == 1) { "wrong exit code: $directory" }
            check(Files.readString(marker) == "shutdown-completed")
            println("PASS: 3 recovery exit checks (nested locks released, cleanup once, exit 1)")
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
