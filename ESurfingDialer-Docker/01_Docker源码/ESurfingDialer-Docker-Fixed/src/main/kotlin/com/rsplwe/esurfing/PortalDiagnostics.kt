package com.rsplwe.esurfing

import java.util.concurrent.TimeUnit

/** Only counts and fixed categories: never retain URLs, headers, XML values or parser messages. */
data class PortalDiagnostics(
    val bodyChars: Int = -1,
    val configBlocks: Int = -1,
    val authFields: Int = -1,
    val ticketFields: Int = -1,
    val bodyBytes: Int = -1,
    val httpStatus: Int = -1,
    val hop: Int = 0,
    val mediaType: PortalMediaType = PortalMediaType.UNKNOWN,
    val cachedConfig: Boolean = false,
) {
    fun summary(): String = "bodyChars=$bodyChars blocks=$configBlocks authFields=$authFields " +
        "ticketFields=$ticketFields bodyBytes=$bodyBytes httpStatus=$httpStatus hop=$hop mediaType=$mediaType cachedConfig=$cachedConfig"
}

enum class PortalMediaType {
    UNKNOWN, HTML, XML, TEXT, OTHER;

    companion object {
        fun fromHeader(value: String?): PortalMediaType = when (value?.substringBefore(';')?.trim()?.lowercase()) {
            null, "" -> UNKNOWN
            "text/html", "application/xhtml+xml" -> HTML
            "text/xml", "application/xml" -> XML
            "text/plain" -> TEXT
            else -> OTHER
        }
    }
}

/** Bounded to one failure; candidate contents are compared in memory and never rendered. */
internal class AuthenticationFailureReports(private val now: () -> Long = System::nanoTime) {
    private data class Key(val code: String, val candidate: PortalCandidate)
    private var lastKey: Key? = null
    private var lastReportAt = 0L
    private var suppressed = 0L

    fun reset() { lastKey = null; suppressed = 0 }

    fun report(failure: AuthenticationFailure, candidate: PortalCandidate, retrySeconds: Long): String? {
        // Timestamp/nonce changes in the page or its size must not defeat log suppression.
        val key = Key(failure.code, candidate.copy(portalBody = null))
        val time = now()
        if (key == lastKey && time - lastReportAt < TimeUnit.SECONDS.toNanos(60)) {
            suppressed++
            return null
        }
        val repeated = if (key == lastKey) suppressed else 0
        lastKey = key
        lastReportAt = time
        suppressed = 0
        return "AUTHORIZATION_FAILED code=${failure.code} deterministic=${failure.deterministic} " +
            "retryIn=${retrySeconds}s suppressed=$repeated" +
            (failure.portalDiagnostics?.let { " ${it.summary()}" } ?: "")
    }
}
