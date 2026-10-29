package com.rsplwe.esurfing

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.NoSuchFileException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*

/** Only publish a complete, flushed file; leave the previous file intact on write failure. */
object StateFiles {
    fun read(file: File, maximumBytes: Int = 32 * 1024): String {
        var attempts = 0
        while (true) try {
            Files.newByteChannel(file.toPath(), READ).use { channel ->
                require(channel.size() <= maximumBytes) { "STATE_TOO_LARGE" }
                val buffer = ByteBuffer.allocate(maximumBytes + 1)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
                require(buffer.position() <= maximumBytes) { "STATE_TOO_LARGE" }
                buffer.flip()
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
            }
        } catch (e: java.nio.file.FileSystemException) {
            if (e !is AccessDeniedException && e !is NoSuchFileException) throw e
            if (++attempts >= 20) throw e
            Thread.sleep(10)
        }
    }

    fun write(file: File, content: String) {
        val target = file.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${file.name}.", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            var attempts = 0
            while (true) try {
                try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
                break
            } catch (e: AccessDeniedException) {
                // Windows readers can briefly deny delete sharing. Never truncate the old file.
                if (++attempts >= 20) throw e
                Thread.sleep(10)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}

/** Flat state schema: encode scalar JSON values and reject malformed/duplicate fields on read. */
object StateJson {
    fun encode(values: Map<String, Any?>): String = values.entries.joinToString(",\n", "{\n", "\n}\n") {
        "  ${quote(it.key)}: ${when (val value = it.value) {
            null -> "null"
            is String -> quote(value)
            is Boolean, is Long, is Int -> value.toString()
            else -> error("Unsupported state value")
        }}"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) append(when (c) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (c.code < 32) "\\u%04x".format(c.code) else c.toString()
        })
        append('"')
    }

    fun decode(content: String): Map<String, Any?> {
        require(content.length <= 32 * 1024) { "STATE_TOO_LARGE" }
        var position = 0
        fun space() { while (position < content.length && content[position] in " \r\n\t") position++ }
        fun take(c: Char) { space(); require(position < content.length && content[position++] == c) { "STATE_JSON_INVALID" } }
        fun string(): String {
            take('"')
            val result = StringBuilder()
            while (position < content.length) {
                val c = content[position++]
                if (c == '"') return result.toString()
                require(c.code >= 32) { "STATE_JSON_INVALID" }
                if (c != '\\') result.append(c) else {
                    require(position < content.length) { "STATE_JSON_INVALID" }
                    when (val escape = content[position++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append(12.toChar())
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(position + 4 <= content.length) { "STATE_JSON_INVALID" }
                            val hex = content.substring(position, position + 4)
                            require(hex.all { it in "0123456789abcdefABCDEF" }) { "STATE_JSON_INVALID" }
                            result.append(hex.toInt(16).toChar()); position += 4
                        }
                        else -> error("STATE_JSON_INVALID")
                    }
                }
            }
            error("STATE_JSON_INVALID")
        }
        take('{'); space()
        val values = linkedMapOf<String, Any?>()
        if (position < content.length && content[position] == '}') position++ else while (true) {
            val key = string(); take(':'); space()
            require(key !in values) { "STATE_DUPLICATE_FIELD" }
            require(position < content.length) { "STATE_JSON_INVALID" }
            val value: Any? = if (content[position] == '"') string() else {
                val start = position
                while (position < content.length && content[position] !in ",} \r\n\t") position++
                val token = content.substring(start, position)
                when (token) {
                    "true" -> true
                    "false" -> false
                    "null" -> null
                    else -> {
                        require(Regex("-?(?:0|[1-9][0-9]*)").matches(token)) { "STATE_JSON_INVALID" }
                        token.toLongOrNull() ?: error("STATE_NUMBER_INVALID")
                    }
                }
            }
            values[key] = value; space()
            require(position < content.length) { "STATE_JSON_INVALID" }
            if (content[position] == '}') { position++; break }
            take(',')
        }
        space(); require(position == content.length) { "STATE_JSON_INVALID" }
        return values
    }
}
