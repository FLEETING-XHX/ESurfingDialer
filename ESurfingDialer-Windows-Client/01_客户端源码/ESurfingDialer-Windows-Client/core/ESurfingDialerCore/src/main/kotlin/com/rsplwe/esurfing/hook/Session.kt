package com.rsplwe.esurfing.hook

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.linux.android.dvm.DvmClass
import com.github.unidbg.linux.android.dvm.DvmObject
import com.rsplwe.esurfing.States
import com.rsplwe.esurfing.NativeSessionLoader
import com.rsplwe.esurfing.NativeSessionHandle
import com.rsplwe.esurfing.AuthenticationFailure
import org.apache.log4j.Logger

class Session(zsm: ByteArray) {

    private val logger: Logger = Logger.getLogger(Session::class.java)
    private val emulator: AndroidEmulator = AndroidMock.getInstance().getEmulator()
    private val method: DvmClass = AndroidMock.getInstance().getJniMethod()
    private val nativeHandle: NativeSessionHandle
    
    init {
        logger.info("Initializing Session...")
        val loaded = NativeSessionLoader.load({ this.load(zsm) }, { this.getAlgoId(it) }, { this.freeHandle(it) })
        nativeHandle = NativeSessionHandle(loaded.first, ::freeHandle)
        States.algoId = loaded.second
    }

    private fun load(zsm: ByteArray): Long {
        return method.callStaticJniMethodLong(emulator, "load([B)J", zsm)
    }

    fun decrypt(hex: String): String = nativeHandle.use { handle ->
        try {
            val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "dec(J[B)[B", handle, hex.toByteArray(Charsets.UTF_8))
            String(r.value as ByteArray, Charsets.UTF_8)
        } catch (_: Exception) { throw AuthenticationFailure("NATIVE_DECRYPT_FAILED", true) }
    }

    private fun getAlgoId(handle: Long): String {
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "aid(J)Ljava/lang/String;", handle)
        return r.value as String
    }

    fun getSessionId(): Long = nativeHandle.use { it }

    fun getKey(): String = nativeHandle.use { handle ->
        val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "key(J)Ljava/lang/String;", handle)
        r.value as String
    }

    fun encrypt(hex: String): String = nativeHandle.use { handle ->
        try {
            val r: DvmObject<*> = method.callStaticJniMethodObject(emulator, "enc(J[B)[B", handle, hex.toByteArray(Charsets.UTF_8))
            String(r.value as ByteArray, Charsets.UTF_8)
        } catch (_: Exception) { throw AuthenticationFailure("NATIVE_ENCRYPT_FAILED", true) }
    }

    fun free() = nativeHandle.free()

    private fun freeHandle(handle: Long) {
        method.callStaticJniMethod(emulator, "free(J)V", handle)
    }
}
