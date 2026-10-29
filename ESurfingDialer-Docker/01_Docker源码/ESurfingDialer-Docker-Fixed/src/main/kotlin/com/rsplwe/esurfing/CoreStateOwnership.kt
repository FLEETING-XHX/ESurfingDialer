package com.rsplwe.esurfing

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Held until process exit: an atomic JSON replacement does not provide writer ownership. */
class CoreStateOwnership private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { channel.close() } }
    companion object {
        fun acquire(directory: File): CoreStateOwnership? {
            directory.mkdirs()
            val channel = FileChannel.open(File(directory, "core-owner.lock").toPath(), CREATE, WRITE)
            return try {
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { channel.close(); null } else CoreStateOwnership(channel, lock)
            } catch (error: Exception) { channel.close(); throw error }
        }
    }
}
