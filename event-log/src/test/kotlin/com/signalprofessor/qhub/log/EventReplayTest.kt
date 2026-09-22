package com.signalprofessor.qhub.log

import com.signalprofessor.qhub.core.capability.CapabilityId
import com.signalprofessor.qhub.core.event.EventEnvelope
import com.signalprofessor.qhub.core.event.EventTimestamp
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventReplayTest {
    private fun event(sequence: Long, nanos: Long) = EventEnvelope(
        eventId = "event-$sequence",
        deviceId = "device",
        sessionId = "session",
        sequence = sequence,
        source = CapabilityId("navigation"),
        eventType = "navigation.gnss",
        timestamp = EventTimestamp(nanos, 1_000 + sequence),
        payload = buildJsonObject {},
    )

    @Test
    fun `recorded intervals drive replay timing`() {
        val replay = EventReplay(listOf(event(0, 1_000_000_000), event(1, 2_250_000_000)))
        assertEquals(0, replay.delayUntilNextMillis())
        assertEquals(0L, replay.next()?.sequence)
        assertEquals(1_250, replay.delayUntilNextMillis())
        assertEquals(1L, replay.next()?.sequence)
        assertNull(replay.next())
        replay.reset()
        assertEquals(0L, replay.next()?.sequence)
    }

    @Test
    fun `out of order timestamps never create negative delay`() {
        val replay = EventReplay(listOf(event(0, 2_000_000_000), event(1, 1_000_000_000)))
        replay.next()
        assertEquals(0, replay.delayUntilNextMillis())
    }
}
