package com.rsplwe.esurfing



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
    val configChars: Int = -1,
    val xmlStage: PortalXmlStage = PortalXmlStage.NONE,
    val xmlLine: Int = -1,
    val xmlColumn: Int = -1,
    val xmlIssue: PortalXmlIssue = PortalXmlIssue.NONE,
    val xmlContext: PortalXmlContext = PortalXmlContext.UNKNOWN,
) {
    fun summary(): String = "bodyChars=$bodyChars blocks=$configBlocks authFields=$authFields " +
        "ticketFields=$ticketFields bodyBytes=$bodyBytes httpStatus=$httpStatus hop=$hop mediaType=$mediaType cachedConfig=$cachedConfig " +
        "configChars=$configChars xmlStage=$xmlStage xmlLine=$xmlLine xmlColumn=$xmlColumn xmlIssue=$xmlIssue xmlContext=$xmlContext"
}

/** Positions refer to the normalized, wrapped XML input, never to a rendered source excerpt. */
enum class PortalXmlStage { NONE, PARSER_SETUP, XML_PARSE }

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
