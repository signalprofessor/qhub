package com.signalprofessor.qhub.eastwing

import com.signalprofessor.qhub.core.capability.CapabilityId
import com.signalprofessor.qhub.core.event.EventEnvelope
import com.signalprofessor.qhub.core.event.EventTimestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTelemetrySenderTest {
    private fun event() = EventEnvelope(
        eventId = "event-1",
        deviceId = "device-1",
        sessionId = "session-1",
        sequence = 0,
        source = CapabilityId("navigation"),
        eventType = "navigation.gnss",
        timestamp = EventTimestamp(1, 2),
        payload = buildJsonObject {},
    )

    @Test
    fun sendsOnlyAfterOptInAndPreservesEventEnvelope() {
        val sent = CountDownLatch(1)
        val body = AtomicReference<String>()
        val token = AtomicReference<String>()
        val sender = LiveTelemetrySender(onStatus = {}, send = { bytes, secret ->
            body.set(bytes.toString(Charsets.UTF_8))
            token.set(secret)
            sent.countDown()
            "{}"
        })
        sender.enqueue(event())
        assertFalse(sender.isEnabled)
        sender.enable("local-test-token")
        sender.enqueue(event())
        assertTrue(sent.await(3, TimeUnit.SECONDS))
        assertEquals("local-test-token", token.get())
        assertTrue(body.get().endsWith("\n"))
        assertTrue(body.get().contains("\"eventId\":\"event-1\""))
        sender.disable()
        assertFalse(sender.isEnabled)
    }
}
