package com.rsplwe.esurfing

import java.nio.file.Files

/** 仅合成目录；不读取环境配置、不加载原生库、不进行认证。 */
object DeviceIdentityRegression {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("docker-identity-regression-").toFile()
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        val file = root.resolve("device-state.json")
        val first = DeviceIdentityStore.load(file, null, null)
        verify(first.clientId.isNotBlank() && first.mac.split(':').size == 6)
        val original = file.readBytes()
        verify(DeviceIdentityStore.load(file, null, null) == first && file.readBytes().contentEquals(original))
        file.writeText("{\n  \"macAddress\": \"AA:BB:CC:DD:EE:FF\", \"clientId\": \"Legacy_ID-42\"\n}")
        val legacy = file.readBytes()
        verify(DeviceIdentityStore.load(file, null, null).clientId == "legacy_id-42" && file.readBytes().contentEquals(legacy))
        val updated = DeviceIdentityStore.load(file, "02:11:22:33:44:55", null)
        verify(updated.mac == "02:11:22:33:44:55" && updated.clientId == "legacy_id-42")
        verify(DeviceIdentityStore.load(file, null, "explicit-id").clientId == "explicit-id")
        val good = file.readBytes()
        for ((mac, id) in listOf("bad-mac" to null, null to "bad\nheader")) {
            try { DeviceIdentityStore.load(file, mac, id); error("invalid override accepted") }
            catch (_: IllegalArgumentException) { verify(file.readBytes().contentEquals(good)) }
        }
        for (content in listOf("{broken-json", "{}", "{\"macAddress\":\"02:11:22:33:44:55\"}",
            "{\"macAddress\":\"bad\",\"clientId\":\"legacy\"}",
            "{\"macAddress\":\"02:11:22:33:44:55\",\"clientId\":42}",
            "{\"macAddress\":\"02:11:22:33:44:55\",\"clientId\":\"\"}")) {
            file.writeText(content)
            for (override in listOf(false, true)) {
                var rejected = false
                try { DeviceIdentityStore.load(file, if (override) "02:11:22:33:44:55" else null, if (override) "explicit" else null) }
                catch (e: IllegalStateException) { rejected = e.message!!.startsWith("DEVICE_IDENTITY_INVALID") }
                verify(rejected && file.readText() == content)
            }
        }
        file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        var rejected = false
        try { DeviceIdentityStore.load(file, null, null) } catch (_: IllegalStateException) { rejected = true }
        verify(rejected && file.readBytes().contentEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        val directory = root.resolve("directory.json").apply { mkdir() }
        rejected = false
        try { DeviceIdentityStore.load(directory, null, null) } catch (_: IllegalStateException) { rejected = true }
        verify(rejected && directory.isDirectory)
        val newFile = root.resolve("explicit.json")
        verify(DeviceIdentityStore.load(newFile, "02:aa:bb:cc:dd:ee", "known-client").clientId == "known-client")
        verify(root.listFiles()!!.none { it.name.endsWith(".tmp") })
        // 真实入口必须在启动健康线程、网络探测和原生会话之前拒绝损坏身份。
        val childRoot = root.resolve("startup").apply { mkdir() }
        val corrupt = childRoot.resolve("device-state.json").apply { writeText("{broken-startup") }
        val javaExecutable = java.io.File(System.getProperty("java.home"), "bin/java").path
        val builder = ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
            "com.rsplwe.esurfing.DialerApp", "--user=synthetic", "--password=fixture")
        builder.environment()["STATE_DIR"] = childRoot.absolutePath
        builder.environment().remove("DIALER_MAC_ADDRESS")
        builder.environment().remove("DIALER_CLIENT_ID")
        builder.redirectErrorStream(true).redirectOutput(childRoot.resolve("process.log"))
        val process = builder.start()
        try {
            verify(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() != 0)
            verify(corrupt.readText() == "{broken-startup")
            verify(!childRoot.resolve("health.json").exists() && childRoot.resolve("process.log").readText().contains("DEVICE_IDENTITY_INVALID"))
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor() } }
        println("Docker identity regression: $checks checks passed")
    }
}
