package com.signalprofessor.qhub.eastwing

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Development-only transport: adb reverse maps Pixel localhost:8000 to the Mac backend. */
internal object LocalTelemetryUploader {
    private const val ENDPOINT = "http://127.0.0.1:8000/v1/events"
    private const val MAX_BYTES = 1_000_000L
    private const val BATCH_BYTES = 900_000
    private const val BATCH_EVENTS = 500

    fun upload(file: File, token: String): String {
        require(token.isNotBlank()) { "Enter the backend token" }
        require(file.isFile && file.length() > 0) { "No mission events in this mission" }
        var received = 0
        var inserted = 0
        var duplicates = 0
        var batches = 0
        val batch = java.io.ByteArrayOutputStream()
        var count = 0
        fun flush() {
            if (count == 0) return
            val receipt = try { JSONObject(uploadPayload(batch.toByteArray(), token)) }
                catch (error: Exception) {
                    throw IllegalStateException("Upload stopped after $received events: ${error.message}", error)
                }
            received += receipt.getInt("received")
            inserted += receipt.getInt("inserted")
            duplicates += receipt.getInt("duplicates")
            batches++
            batch.reset()
            count = 0
        }
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter(String::isNotBlank).forEach { line ->
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                require(bytes.size <= BATCH_BYTES) { "A mission event exceeds the backend batch limit" }
                if (count >= BATCH_EVENTS || batch.size() + bytes.size > BATCH_BYTES) flush()
                batch.write(bytes)
                count++
            }
        }
        flush()
        require(received > 0) { "Mission log contains no events" }
        return JSONObject().put("received", received).put("inserted", inserted)
            .put("duplicates", duplicates).put("batches", batches).toString()
    }

    fun uploadPayload(payload: ByteArray, token: String): String {
        require(token.isNotBlank()) { "Enter the backend token" }
        require(payload.isNotEmpty() && payload.size <= MAX_BYTES) { "Invalid telemetry batch size" }
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/x-ndjson")
            setFixedLengthStreamingMode(payload.size)
        }
        try {
            connection.outputStream.use { output -> output.write(payload) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Backend HTTP $code: $body")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
