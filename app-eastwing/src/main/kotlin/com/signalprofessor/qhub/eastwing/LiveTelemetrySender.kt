package com.signalprofessor.qhub.eastwing

import com.signalprofessor.qhub.core.event.EventEnvelope
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Opt-in, foreground-only best-effort telemetry. The mission NDJSON log remains the source of
 * truth; a failed live send can be recovered with "Send last mission to Mac".
 */
internal class LiveTelemetrySender(
    private val onStatus: (String) -> Unit,
    private val send: (ByteArray, String) -> String = LocalTelemetryUploader::uploadPayload,
) {
    private data class Session(
        val token: String,
        val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(32),
        val active: AtomicBoolean = AtomicBoolean(true),
        var worker: Thread? = null,
        var sent: Int = 0,
        var failed: Int = 0,
        var dropped: Int = 0,
    )

    private val json = Json { encodeDefaults = true }
    @Volatile private var session: Session? = null

    val isEnabled: Boolean get() = session != null

    fun enable(token: String) {
        require(token.isNotBlank()) { "Enter the backend token first" }
        check(session == null) { "Live telemetry is already on" }
        val current = Session(token)
        session = current
        current.worker = Thread({
            while (current.active.get()) {
                val payload = try {
                    current.queue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                try {
                    send(payload, current.token)
                    current.sent += 1
                    report(current, "Live ON: " + current.sent + " sent, " + current.failed + " failed")
                } catch (error: Exception) {
                    current.failed += 1
                    report(current, "Live ON: " + current.sent + " sent, " + current.failed +
                        " failed (" + error.message + ")")
                }
            }
        }, "qhub-live-telemetry").apply { isDaemon = true; start() }
        report(current, "Live ON: waiting for GNSS events")
    }

    fun enqueue(event: EventEnvelope) {
        val current = session ?: return
        val payload = (json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
        if (!current.queue.offer(payload)) {
            current.dropped += 1
            report(current, "Live ON: queue full, " + current.dropped + " not sent; local log is safe")
        }
    }

    fun disable() {
        val current = session ?: return
        session = null
        current.active.set(false)
        val unsent = current.queue.size + current.dropped
        current.queue.clear()
        current.worker?.interrupt()
        onStatus("Live OFF: " + current.sent + " sent, " + current.failed +
            " failed, " + unsent + " not sent. Local log retained")
    }

    private fun report(current: Session, message: String) {
        if (session === current) onStatus(message)
    }
}
