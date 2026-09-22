package com.signalprofessor.qhub.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val ROTATION_VECTOR_EVENT_TYPE = "navigation.rotation_vector"

data class RotationVectorSample(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float?,
    val headingAccuracyRadians: Float?,
    val sensorElapsedRealtimeNanos: Long,
    val sensorAccuracy: Int,
)

fun RotationVectorSample.toEventPayload(): JsonObject = buildJsonObject {
    put("x", x)
    put("y", y)
    put("z", z)
    w?.let { put("w", it) }
    headingAccuracyRadians?.let { put("headingAccuracyRadians", it) }
    put("sensorElapsedRealtimeNanos", sensorElapsedRealtimeNanos)
    put("sensorAccuracy", sensorAccuracy)
}

/** Android fused rotation vector, sampled at about 1 Hz in device coordinates. */
class RotationVectorSource(
    context: Context,
    private val onSample: (RotationVectorSample) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var running = false
    private var lastSampleNanos = Long.MIN_VALUE

    fun start(): StartResult {
        if (running) return StartResult.AlreadyRunning
        val rotationSensor = sensor ?: return StartResult.Unavailable
        lastSampleNanos = Long.MIN_VALUE
        running = sensorManager.registerListener(
            this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()),
        )
        return if (running) StartResult.Started else StartResult.RegistrationFailed
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        if (lastSampleNanos != Long.MIN_VALUE && event.timestamp - lastSampleNanos < 1_000_000_000L) return
        val values = event.values
        if (values.size < 3 || !values.take(3).all(Float::isFinite)) return
        val w = values.getOrNull(3)?.takeIf(Float::isFinite)
        val accuracy = values.getOrNull(4)?.takeIf(Float::isFinite)
        lastSampleNanos = event.timestamp
        onSample(RotationVectorSample(values[0], values[1], values[2], w, accuracy,
            event.timestamp, event.accuracy))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    enum class StartResult { Started, AlreadyRunning, Unavailable, RegistrationFailed }
}
