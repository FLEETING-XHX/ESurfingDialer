package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.MacAddress
import org.apache.log4j.Logger
import java.io.File
import java.util.UUID

object DeviceIdentityStore {
    private val logger: Logger = Logger.getLogger(DeviceIdentityStore::class.java)
    private val macRegex = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    fun loadIntoStates() {
        val file = File(States.rootDir, "device-state.json")
        val existing = if (file.isFile) file.readText() else ""

        val envMac = System.getenv("DIALER_MAC_ADDRESS")?.trim()?.takeIf { it.isNotEmpty() }
        val envClientId = System.getenv("DIALER_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }

        val macAddress = envMac
            ?.also { require(macRegex.matches(it)) { "Invalid DIALER_MAC_ADDRESS: $it" } }
            ?: readJsonString(existing, "macAddress")
                ?.takeIf { macRegex.matches(it) }
            ?: MacAddress.random()

        val clientId = envClientId
            ?: readJsonString(existing, "clientId")
                ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().lowercase()

        States.macAddress = macAddress.lowercase()
        States.clientId = clientId.lowercase()

        write(file, States.macAddress, States.clientId)
        logger.info("DEVICE_IDENTITY_LOADED mac=${maskMac(States.macAddress)} clientId=${maskId(States.clientId)}")
    }

    private fun readJsonString(content: String, field: String): String? {
        return Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(content)?.groupValues?.get(1)
    }

    private fun write(file: File, macAddress: String, clientId: String) {
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "macAddress": "$macAddress",
              "clientId": "$clientId"
            }
            """.trimIndent() + "\n"
        )
    }

    private fun maskMac(mac: String): String {
        val parts = mac.split(":")
        return if (parts.size == 6) "${parts[0]}:${parts[1]}:xx:xx:${parts[4]}:${parts[5]}" else "***"
    }

    private fun maskId(id: String): String =
        if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else "***"
}
