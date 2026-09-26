package com.rsplwe.esurfing.hook

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.linux.android.dvm.DvmClass
import com.github.unidbg.linux.android.dvm.DvmObject
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.NativeSessionLoader
import org.apache.log4j.Logger

class Session(zsm: ByteArray) {

    private val logger: Logger = Logger.getLogger(Session::class.java)
    private val emulator: AndroidEmulator = AndroidMock.getInstance().getEmulator()
    private val method: DvmClass = AndroidMock.getInstance().getJniMethod()
    private val sessionId: Long
    private val clientId: String
    
    init {
        logger.info("Initializing Session...")
        val loaded = NativeSessionLoader.load({ this.load(zsm) }, { this.getAlgoId(it) }, { this.freeHandle(it) })
        sessionId = loaded.first
        clientId = States.clientId
        States.algoId = loaded.second
    }

    private fun load(zsm: ByteArray): Long {
        return method.callStaticJniMethodLong(emulator, "load([B)J", zsm)
    }

    fun decrypt(hex: String): String {
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "dec(J[B)[B", sessionId, hex.toByteArray(Charsets.UTF_8))
        return String((r.value as ByteArray))
    }

    private fun getAlgoId(handle: Long): String {
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "aid(J)Ljava/lang/String;", handle)
        return r.value as String
    }

    fun getSessionId(): Long {
        return this.sessionId
    }

    fun getKey(): String {
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "key(J)Ljava/lang/String;", sessionId)
        return r.value as String
    }

    fun encrypt(hex: String): String {
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "enc(J[B)[B", sessionId, hex.toByteArray(Charsets.UTF_8))
        return String((r.value as ByteArray))
    }

    fun free() {
        freeHandle(sessionId)
    }

    private fun freeHandle(handle: Long) {
        method.callStaticJniMethod(emulator, "free(J)V", handle)
    }
}
