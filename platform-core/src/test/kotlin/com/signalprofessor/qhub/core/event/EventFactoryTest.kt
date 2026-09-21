package com.signalprofessor.qhub.core.event

import com.signalprofessor.qhub.core.capability.CapabilityId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class EventFactoryTest {
    @Test
    fun `factory assigns stable identity dual time and increasing sequence`() {
        val factory = EventFactory(
            deviceId = "pixel-8",
            sessionId = "mission-1",
            clock = QhubClock { EventTimestamp(123_000_000, 1_700_000_000_000) },
            initialSequence = 7,
            eventIdFactory = { "event-id" },
        )

        val first = factory.create(
            CapabilityId("navigation"),
            "navigation.gnss",
            buildJsonObject { put("latitudeDegrees", 58.0) },
        )
        val second = factory.create(
            CapabilityId("navigation"),
            "navigation.gnss",
            buildJsonObject { put("latitudeDegrees", 58.1) },
        )

        assertEquals(7, first.sequence)
        assertEquals(8, second.sequence)
        assertEquals("pixel-8", first.deviceId)
        assertEquals("mission-1", first.sessionId)
        assertEquals(123_000_000, first.timestamp.monotonicNanos)
        assertEquals(1_700_000_000_000, first.timestamp.utcEpochMillis)
        assertEquals(CURRENT_EVENT_SCHEMA_VERSION, first.schemaVersion)
    }
}
