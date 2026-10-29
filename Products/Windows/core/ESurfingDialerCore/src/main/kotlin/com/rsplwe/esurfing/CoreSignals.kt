package com.rsplwe.esurfing

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object CoreSignals {
    private val clientSignal = Semaphore(0)
    private val networkSignal = Semaphore(0)

    fun requestNetworkRecheck() {
        wakeNetworkMonitor()
        wakeClient()
        HealthStatus.requestWrite()
    }

    fun wakeClient() = signal(clientSignal)

    fun wakeNetworkMonitor() = signal(networkSignal)

    fun waitForClient(timeoutMillis: Long): Boolean = waitFor(clientSignal, timeoutMillis)

    fun waitForNetworkMonitor(timeoutMillis: Long): Boolean = waitFor(networkSignal, timeoutMillis)

    private fun signal(semaphore: Semaphore) {
        if (semaphore.availablePermits() == 0) semaphore.release()
    }

    private fun waitFor(semaphore: Semaphore, timeoutMillis: Long): Boolean {
        val signaled = semaphore.tryAcquire(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        semaphore.drainPermits()
        return signaled
    }
}
