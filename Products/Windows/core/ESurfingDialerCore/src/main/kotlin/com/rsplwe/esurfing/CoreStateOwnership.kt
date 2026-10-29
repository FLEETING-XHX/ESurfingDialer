package com.rsplwe.esurfing

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** The file is deliberately retained: deleting a lock inode permits a second live owner. */
class CoreStateOwnership private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { channel.close() } }

    companion object {
        fun acquire(directory: File): CoreStateOwnership? {
            directory.mkdirs()
            val channel = FileChannel.open(File(directory, "core-owner.lock").toPath(), CREATE, WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            catch (error: Exception) { channel.close(); throw error }
            if (lock == null) { channel.close(); return null }
            return CoreStateOwnership(channel, lock)
        }
    }
}

/** EOF is loss of the desktop owner, with the same lifecycle as an explicit stop. */
internal object WindowsControl {
    fun run(read: () -> String?, stop: () -> Unit, command: (String) -> Unit) {
        while (true) {
            val line = read()
            if (line == null || line.trim().equals("stop", ignoreCase = true)) { stop(); return }
            command(line.trim().lowercase())
        }
    }
}

/** Also covers owner death before the desktop can attach its kill-on-close Job Object. */
internal object CoreParentMonitor {
    fun watch(parentPid: Long, stop: () -> Unit) {
        val parent = ProcessHandle.of(parentPid).orElse(null)
        if (parent == null || !parent.isAlive) { stop(); return }
        parent.onExit().thenRun { stop() }
    }
}
