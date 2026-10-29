package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.MacAddress
import org.apache.log4j.Logger
import java.io.File
import java.util.UUID

object DeviceIdentityStore {
    private val logger: Logger = Logger.getLogger(DeviceIdentityStore::class.java)
    private val macRegex = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    internal data class Identity(val mac: String, val clientId: String)

    /** 已有身份损坏时保留原文，禁止把解析失败当成首次初始化。 */
    internal fun load(file: File, envMac: String?, envClientId: String?): Identity {
        fun validId(value: String) = value.isNotBlank() && value.all { it.code in 33..126 }
        val macOverride = envMac?.trim()?.takeIf { it.isNotEmpty() }
        val idOverride = envClientId?.trim()?.takeIf { it.isNotEmpty() }
        require(macOverride == null || macRegex.matches(macOverride)) { "DIALER_MAC_ADDRESS 格式无效" }
        require(idOverride == null || validId(idOverride)) { "DIALER_CLIENT_ID 格式无效" }
        val existing = if (file.exists()) {
            try {
                require(file.isFile)
                val values = StateJson.decode(StateFiles.read(file))
                val mac = values["macAddress"] as? String ?: error("missing MAC")
                val id = values["clientId"] as? String ?: error("missing client ID")
                require(macRegex.matches(mac) && validId(id))
                Identity(mac.lowercase(), id.lowercase())
            } catch (_: Exception) {
                throw IllegalStateException("DEVICE_IDENTITY_INVALID: 已有设备身份无法读取或校验，原文件已保留；请恢复有效身份文件后重试。")
            }
        } else null
        val identity = Identity(macOverride?.lowercase() ?: existing?.mac ?: MacAddress.random(),
            idOverride?.lowercase() ?: existing?.clientId ?: UUID.randomUUID().toString().lowercase())
        if (identity != existing) write(file, identity.mac, identity.clientId)
        return identity
    }

    fun loadIntoStates() {
        val file = File(States.rootDir, "device-state.json")
        val envMac = System.getenv("DIALER_MAC_ADDRESS")?.trim()?.takeIf { it.isNotEmpty() }
        val envClientId = System.getenv("DIALER_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }

        val identity = load(file, envMac, envClientId)
        States.macAddress = identity.mac
        States.clientId = identity.clientId
        logger.info("DEVICE_IDENTITY_LOADED mac=${maskMac(States.macAddress)} clientId=${maskId(States.clientId)}")
    }

    private fun write(file: File, macAddress: String, clientId: String) {
        StateFiles.write(file, StateJson.encode(linkedMapOf("macAddress" to macAddress, "clientId" to clientId)))
    }

    private fun maskMac(mac: String): String {
        val parts = mac.split(":")
        return if (parts.size == 6) "${parts[0]}:${parts[1]}:xx:xx:${parts[4]}:${parts[5]}" else "***"
    }

    private fun maskId(id: String): String =
        if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else "***"
}
