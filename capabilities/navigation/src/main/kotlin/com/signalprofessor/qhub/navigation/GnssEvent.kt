package com.signalprofessor.qhub.navigation

import android.location.Location
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val GNSS_EVENT_TYPE = "navigation.gnss"

data class GnssSample(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val horizontalAccuracyMeters: Float?,
    val verticalAccuracyMeters: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingAccuracyDegrees: Float?,
    val provider: String,
    val sensorElapsedRealtimeNanos: Long,
    val sensorUtcEpochMillis: Long,
)

fun Location.toGnssSample(): GnssSample = GnssSample(
    latitudeDegrees = latitude,
    longitudeDegrees = longitude,
    altitudeMeters = altitude.takeIf { hasAltitude() },
    speedMetersPerSecond = speed.takeIf { hasSpeed() },
    bearingDegrees = bearing.takeIf { hasBearing() },
    horizontalAccuracyMeters = accuracy.takeIf { hasAccuracy() },
    verticalAccuracyMeters = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
    speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond.takeIf { hasSpeedAccuracy() },
    bearingAccuracyDegrees = bearingAccuracyDegrees.takeIf { hasBearingAccuracy() },
    provider = provider.orEmpty(),
    sensorElapsedRealtimeNanos = elapsedRealtimeNanos,
    sensorUtcEpochMillis = time,
)

fun GnssSample.toEventPayload(): JsonObject = buildJsonObject {
    put("latitudeDegrees", latitudeDegrees)
    put("longitudeDegrees", longitudeDegrees)
    altitudeMeters?.let { put("altitudeMeters", it) }
    speedMetersPerSecond?.let { put("speedMetersPerSecond", it) }
    bearingDegrees?.let { put("bearingDegrees", it) }
    horizontalAccuracyMeters?.let { put("horizontalAccuracyMeters", it) }
    verticalAccuracyMeters?.let { put("verticalAccuracyMeters", it) }
    speedAccuracyMetersPerSecond?.let { put("speedAccuracyMetersPerSecond", it) }
    bearingAccuracyDegrees?.let { put("bearingAccuracyDegrees", it) }
    put("provider", provider)
    put("sensorElapsedRealtimeNanos", sensorElapsedRealtimeNanos)
    put("sensorUtcEpochMillis", sensorUtcEpochMillis)
}
