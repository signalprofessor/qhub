package com.signalprofessor.qhub.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PressureSampleTest {
    @Test
    fun rawPressurePayloadKeepsSensorTimeWithoutInventingAltitude() {
        val payload = PressureSample(1008.25f, 123456789L, 3).toEventPayload()
        assertEquals(1008.25, payload["pressureHectopascals"]!!.toString().toDouble(), 0.001)
        assertEquals("123456789", payload["sensorElapsedRealtimeNanos"]!!.toString())
        assertEquals("3", payload["sensorAccuracy"]!!.toString())
        assertFalse(payload.containsKey("altitudeMeters"))
    }
}
