package com.signalprofessor.qhub.core.capability

import com.signalprofessor.qhub.core.event.EventFactory
import com.signalprofessor.qhub.core.event.EventSink
import com.signalprofessor.qhub.core.event.EventTimestamp
import com.signalprofessor.qhub.core.event.QhubClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AbstractCapabilityTest {
    private class TestCapability : AbstractCapability(CapabilityId("test"))

    private val context = CapabilityContext(
        events = EventSink { },
        eventFactory = EventFactory(
            deviceId = "device",
            sessionId = "session",
            clock = QhubClock { EventTimestamp(1, 2) },
        ),
    )

    @Test
    fun `capability follows normal lifecycle`() = runBlocking {
        val capability = TestCapability()

        capability.initialize(context)
        capability.configure(CapabilityConfig())
        capability.start()
        capability.pause()
        capability.start()
        capability.stop()

        assertEquals(CapabilityState.Stopped, capability.state.value)
        assertEquals(CapabilityState.Stopped, capability.health.value.state)
    }

    @Test
    fun `disabled capability does not start`() = runBlocking {
        val capability = TestCapability()

        capability.initialize(context)
        capability.configure(CapabilityConfig(enabled = false))
        capability.start()

        assertEquals(CapabilityState.Stopped, capability.state.value)
        assertEquals("Disabled by configuration", capability.health.value.message)
    }
}
