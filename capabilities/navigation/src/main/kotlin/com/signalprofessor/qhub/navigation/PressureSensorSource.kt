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

const val PRESSURE_EVENT_TYPE = "navigation.pressure"

data class PressureSample(
    val pressureHectopascals: Float,
    val sensorElapsedRealtimeNanos: Long,
    val sensorAccuracy: Int,
)

fun PressureSample.toEventPayload(): JsonObject = buildJsonObject {
    put("pressureHectopascals", pressureHectopascals)
    put("sensorElapsedRealtimeNanos", sensorElapsedRealtimeNanos)
    put("sensorAccuracy", sensorAccuracy)
}

/** Raw pressure at about 1 Hz; no sea-level correction or altitude inference. */
class PressureSensorSource(
    context: Context,
    private val onSample: (PressureSample) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private var running = false
    private var lastSampleNanos = Long.MIN_VALUE

    fun start(): StartResult {
        if (running) return StartResult.AlreadyRunning
        val pressureSensor = sensor ?: return StartResult.Unavailable
        lastSampleNanos = Long.MIN_VALUE
        running = sensorManager.registerListener(
            this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()),
        )
        return if (running) StartResult.Started else StartResult.RegistrationFailed
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_PRESSURE) return
        val pressure = event.values[0]
        if (!pressure.isFinite() || pressure <= 0f) return
        if (lastSampleNanos != Long.MIN_VALUE &&
            event.timestamp - lastSampleNanos < 1_000_000_000L
        ) return
        lastSampleNanos = event.timestamp
        onSample(PressureSample(pressure, event.timestamp, event.accuracy))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    enum class StartResult { Started, AlreadyRunning, Unavailable, RegistrationFailed }
}
