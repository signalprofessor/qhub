package com.signalprofessor.qhub.log

import com.signalprofessor.qhub.core.capability.CapabilityId
import com.signalprofessor.qhub.core.event.EventEnvelope
import com.signalprofessor.qhub.core.event.EventTimestamp
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class NdjsonEventLogTest {
    @Test
    fun `log round trip preserves ordered events exactly`() {
        val file = Files.createTempFile("qhub-events", ".ndjson").toFile()
        val events = (0L..2L).map { sequence ->
            EventEnvelope(
                eventId = "event-$sequence",
                deviceId = "device",
                sessionId = "session",
                sequence = sequence,
                source = CapabilityId("navigation"),
                eventType = "navigation.gnss",
                timestamp = EventTimestamp(sequence + 10, sequence + 1000),
                payload = buildJsonObject { put("latitudeDegrees", 58.0 + sequence) },
            )
        }

        NdjsonEventWriter(file).use { writer -> events.forEach(writer::append) }
        val restored = NdjsonEventReader.read(file)
        val replayed = mutableListOf<EventEnvelope>()
        EventReplay(restored).replay(replayed::add)

        assertEquals(events, restored)
        assertEquals(events, replayed)
    }
}
