package com.signalprofessor.qhub.log

import com.signalprofessor.qhub.core.event.EventEnvelope
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val eventJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class NdjsonEventWriter(
    val file: File,
) : Closeable {
    private val writer: BufferedWriter

    init {
        require(file.parentFile?.let { it.exists() || it.mkdirs() } != false) {
            "Could not create log directory for ${file.absolutePath}"
        }
        writer = file.outputStream().bufferedWriter(StandardCharsets.UTF_8)
    }

    @Synchronized
    fun append(event: EventEnvelope) {
        writer.append(eventJson.encodeToString(event))
        writer.newLine()
        writer.flush()
    }

    fun sizeBytes(): Long = file.length()

    @Synchronized
    override fun close() {
        writer.close()
    }
}

object NdjsonEventReader {
    fun read(file: File): List<EventEnvelope> {
        if (!file.exists()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }.mapIndexed { index, line ->
                runCatching { eventJson.decodeFromString<EventEnvelope>(line) }
                    .getOrElse { error("Invalid event at line ${index + 1}: ${it.message}") }
            }.toList()
        }
    }
}
