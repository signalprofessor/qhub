package com.signalprofessor.qhub.eastwing

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Development-only transport: adb reverse maps Pixel localhost:8000 to the Mac backend. */
internal object LocalTelemetryUploader {
    private const val ENDPOINT = "http://127.0.0.1:8000/v1/events"
    private const val MAX_BYTES = 1_000_000L

    fun upload(file: File, token: String): String {
        require(token.isNotBlank()) { "Enter the backend token" }
        require(file.isFile && file.length() > 0) { "No GNSS events in this mission" }
        require(file.length() <= MAX_BYTES) { "Mission exceeds the first backend's 1 MB batch limit" }

        return uploadPayload(file.readBytes(), token)
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
