package com.rsplwe.esurfing

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class LoginResponse(val keepUrl: String, val termUrl: String, val retrySeconds: Long)

/** Parse required protocol fields before a response can confirm authentication. Never log XML. */
object AuthenticationProtocol {
    const val MAX_XML_CHARS = 64 * 1024

    fun escape(value: String): String = buildString {
        value.forEach { c -> append(when (c) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&apos;"
            else -> c.toString()
        }) }
    }

    private fun response(xml: String, stage: String): Element {
        if (xml.length > MAX_XML_CHARS) throw AuthenticationFailure("${stage}_XML_TOO_LARGE", true)
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
            return builder.parse(InputSource(StringReader(xml))).documentElement
        } catch (_: Exception) {
            throw AuthenticationFailure("${stage}_XML_INVALID", true)
        }
    }

    private fun field(root: Element, name: String, stage: String): String {
        val matches = root.getElementsByTagName(name)
        if (matches.length != 1) throw AuthenticationFailure("${stage}_FIELDS_INVALID", true)
        val element = matches.item(0)
        if (element.parentNode != root) throw AuthenticationFailure("${stage}_FIELDS_INVALID", true)
        val children = element.childNodes
        for (i in 0 until children.length) {
            if (children.item(i).nodeType == Node.ELEMENT_NODE)
                throw AuthenticationFailure("${stage}_FIELDS_INVALID", true)
        }
        return element.textContent.trim().takeIf { it.isNotEmpty() }
            ?: throw AuthenticationFailure("${stage}_FIELDS_INVALID", true)
    }

    private fun interval(value: String, stage: String): Long {
        if (!value.all { it in '0'..'9' }) throw AuthenticationFailure("${stage}_INTERVAL_INVALID", true)
        val seconds = value.toLongOrNull()?.takeIf { it > 0 }
            ?: throw AuthenticationFailure("${stage}_INTERVAL_INVALID", true)
        return seconds.coerceIn(5, RuntimeConfig.heartbeatIntervalMaxSeconds)
    }

    fun ticket(xml: String): String = field(response(xml, "TICKET"), "ticket", "TICKET")

    fun login(xml: String): LoginResponse {
        val root = response(xml, "LOGIN")
        return LoginResponse(
            PortalConfiguration.address(field(root, "keep-url", "LOGIN"), "LOGIN_URL_INVALID").toString(),
            PortalConfiguration.address(field(root, "term-url", "LOGIN"), "LOGIN_URL_INVALID").toString(),
            interval(field(root, "keep-retry", "LOGIN"), "LOGIN"),
        )
    }

    fun heartbeat(xml: String): Long = interval(field(response(xml, "HEARTBEAT"), "interval", "HEARTBEAT"), "HEARTBEAT")
}
