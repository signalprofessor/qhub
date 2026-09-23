package com.signalprofessor.qhub.navigation

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ImuBatchTest {
    @Test
    fun payloadPreservesRawSamplesBiasAndSensorTime() {
        val payload = ImuBatch(
            sensor = "gyroscope",
            androidSensorType = 16,
            requestedSamplingPeriodMicros = 10_000,
            samples = listOf(
                ImuRawSample(123456789L, 1f, 2f, 3f, .1f, .2f, .3f, 3),
                ImuRawSample(123466789L, 4f, 5f, 6f, null, null, null, 2),
            ),
        ).toEventPayload()

        assertEquals("\"gyroscope\"", payload["sensor"].toString())
        assertEquals("2", payload["sampleCount"].toString())
        val samples = payload["samples"]!!.jsonArray
        assertEquals("123456789", samples[0].jsonArray[0].toString())
        assertEquals("0.1", samples[0].jsonArray[4].toString())
        assertEquals(JsonNull, samples[1].jsonArray[4])
        assertEquals("2", samples[1].jsonArray[7].toString())
    }
}
