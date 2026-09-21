package com.signalprofessor.qhub.core.event

import com.signalprofessor.qhub.core.capability.CapabilityId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class EventFactory(
    private val deviceId: String,
    private val sessionId: String,
    private val clock: QhubClock,
    initialSequence: Long = 0,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val nextSequence = AtomicLong(initialSequence)

    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(initialSequence >= 0) { "initialSequence must be non-negative" }
    }

    fun <P : EventPayload> create(
        source: CapabilityId,
        eventType: String,
        payload: P,
    ): EventEnvelope<P> = EventEnvelope(
        eventId = eventIdFactory(),
        deviceId = deviceId,
        sessionId = sessionId,
        sequence = nextSequence.getAndIncrement(),
        source = source,
        eventType = eventType,
        timestamp = clock.now(),
        payload = payload,
    )
}
