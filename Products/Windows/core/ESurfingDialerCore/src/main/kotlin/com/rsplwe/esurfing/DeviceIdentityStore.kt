package com.rsplwe.esurfing

import com.rsplwe.esurfing.utils.MacAddress
import org.apache.log4j.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

data class DeviceIdentity(val macAddress: String, val clientId: String)

/** Existing identity is never repaired by inventing missing fields. The caller holds the core state lock. */
internal class DeviceIdentityRepository(private val directory: File,
    private val write: (File, String) -> Unit = ::writeIdentityAtomically) {
    private val file = File(directory, "device-state.json")
    private val backup = File(directory, "device-state.json.bak")
    private val macRegex = Regex("[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}")
    // Preserve legacy printable header IDs, while excluding quotes/control characters and oversized values.
    private val idRegex = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun load(envMac: String? = null, envClientId: String? = null): DeviceIdentity {
        require(envMac == null || macRegex.matches(envMac)) { "DIALER_MAC_ADDRESS_INVALID" }
        require(envClientId == null || idRegex.matches(envClientId)) { "DIALER_CLIENT_ID_INVALID" }
        val primary = read(file)
        val previous = read(backup)
        val hasHistory = file.exists() || backup.exists()
        val base = primary ?: previous ?: if (hasHistory) {
            // Explicit complete identity is the only supported override of unrecoverable history.
            if (envMac != null && envClientId != null) DeviceIdentity(envMac, envClientId)
            else throw IllegalStateException("DEVICE_IDENTITY_INVALID: 设备身份文件损坏且无有效备份，原文件已保留，请检查 device-state.json。")
        } else DeviceIdentity(MacAddress.random(), UUID.randomUUID().toString())
        val selected = DeviceIdentity((envMac ?: base.macAddress).lowercase(), (envClientId ?: base.clientId).lowercase())
        if (primary != selected) {
            if (file.exists() && primary == null) preserve(file)
            if (previous == null && backup.exists()) preserve(backup)
            // Establish recoverable history before creating/replacing the primary file.
            write(backup, encode(primary ?: previous ?: selected))
            write(file, encode(selected))
        } else if (previous != selected) {
            if (backup.exists() && previous == null) preserve(backup)
            write(backup, encode(selected))
        }
        return selected
    }

    private fun read(path: File): DeviceIdentity? {
        if (!path.exists()) return null
        if (!path.isFile || path.length() > 4096) return null
        val text = path.readText() // Access errors propagate; they do not mean a missing identity.
        val member = "\"(?:macAddress|clientId)\"\\s*:\\s*\"[^\"\\\\\\r\\n]*\""
        if (!Regex("\\s*\\{\\s*$member\\s*,\\s*$member\\s*\\}\\s*").matches(text)) return null
        val fields = Regex("\"(macAddress|clientId)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).toList()
        if (fields.map { it.groupValues[1] }.toSet().size != 2) return null
        val mac = fields.single { it.groupValues[1] == "macAddress" }.groupValues[2]
        val id = fields.single { it.groupValues[1] == "clientId" }.groupValues[2]
        return if (macRegex.matches(mac) && idRegex.matches(id)) DeviceIdentity(mac.lowercase(), id.lowercase()) else null
    }

    private fun preserve(path: File) {
        check(path.isFile) { "DEVICE_IDENTITY_INVALID_PATH" }
        Files.copy(path.toPath(), File(directory, "${path.name}.corrupt-${UUID.randomUUID()}").toPath())
    }

    private fun encode(identity: DeviceIdentity) = "{\n  \"macAddress\": \"${identity.macAddress}\",\n  \"clientId\": \"${identity.clientId}\"\n}\n"
}

private fun writeIdentityAtomically(file: File, text: String) {
    file.parentFile.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), ".device-state-", ".tmp")
    try {
        FileChannel.open(temp, WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try { Files.move(temp, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

object DeviceIdentityStore {
    private val logger = Logger.getLogger(DeviceIdentityStore::class.java)
    fun loadIntoStates() {
        fun env(name: String) = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        val identity = DeviceIdentityRepository(States.rootDir).load(env("DIALER_MAC_ADDRESS"), env("DIALER_CLIENT_ID"))
        States.macAddress = identity.macAddress
        States.clientId = identity.clientId
        logger.info("DEVICE_IDENTITY_LOADED mac=${maskMac(identity.macAddress)} clientId=${maskId(identity.clientId)}")
    }
    private fun maskMac(mac: String): String {
        val parts = mac.split(":")
        return if (parts.size == 6) "${parts[0]}:${parts[1]}:xx:xx:${parts[4]}:${parts[5]}" else "***"
    }
    private fun maskId(id: String): String = if (id.length > 8) "${id.take(4)}...${id.takeLast(4)}" else "***"
}
