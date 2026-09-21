package com.signalprofessor.qhub.core.event

import com.signalprofessor.qhub.core.capability.CapabilityId

const val CURRENT_EVENT_SCHEMA_VERSION: Int = 1

/** Marker for capability-owned event payloads. */
interface EventPayload

/** Monotonic time orders measurements; UTC time supports backend correlation. */
data class EventTimestamp(
    val monotonicNanos: Long,
    val utcEpochMillis: Long,
) {
    init {
        require(monotonicNanos >= 0) { "monotonicNanos must be non-negative" }
        require(utcEpochMillis >= 0) { "utcEpochMillis must be non-negative" }
    }
}

data class EventEnvelope<out P : EventPayload>(
    val schemaVersion: Int = CURRENT_EVENT_SCHEMA_VERSION,
    val eventId: String,
    val deviceId: String,
    val sessionId: String,
    val sequence: Long,
    val source: CapabilityId,
    val eventType: String,
    val timestamp: EventTimestamp,
    val payload: P,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sequence >= 0) { "sequence must be non-negative" }
        require(eventType.isNotBlank()) { "eventType must not be blank" }
    }
}

fun interface EventSink {
    suspend fun publish(event: EventEnvelope<EventPayload>)
}

fun interface QhubClock {
    fun now(): EventTimestamp
}
