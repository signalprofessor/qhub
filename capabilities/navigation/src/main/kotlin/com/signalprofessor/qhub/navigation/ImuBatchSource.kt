package com.signalprofessor.qhub.navigation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val IMU_BATCH_EVENT_TYPE = "navigation.imu_batch"

private const val REQUESTED_PERIOD_MICROS = 10_000
private const val BATCH_DURATION_NANOS = 1_000_000_000L

data class ImuRawSample(
    val sensorElapsedRealtimeNanos: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val biasX: Float?,
    val biasY: Float?,
    val biasZ: Float?,
    val sensorAccuracy: Int,
)

data class ImuBatch(
    val sensor: String,
    val androidSensorType: Int,
    val requestedSamplingPeriodMicros: Int,
    val samples: List<ImuRawSample>,
)

fun ImuBatch.toEventPayload(): JsonObject = buildJsonObject {
    put("sensor", sensor)
    put("androidSensorType", androidSensorType)
    put("requestedSamplingPeriodMicros", requestedSamplingPeriodMicros)
    put("sampleCount", samples.size)
    putJsonArray("sampleFields") {
        listOf("sensorElapsedRealtimeNanos", "x", "y", "z", "biasX", "biasY", "biasZ", "sensorAccuracy")
            .forEach(::add)
    }
    putJsonArray("samples") {
        samples.forEach { sample ->
            add(buildJsonArray {
                add(sample.sensorElapsedRealtimeNanos)
                add(sample.x)
                add(sample.y)
                add(sample.z)
                sample.biasX?.let(::add) ?: add(JsonNull)
                sample.biasY?.let(::add) ?: add(JsonNull)
                sample.biasZ?.let(::add) ?: add(JsonNull)
                add(sample.sensorAccuracy)
            })
        }
    }
}

/**
 * Lossless one-second batches of raw IMU samples. Sampling is requested at 100 Hz; sensor
 * timestamps, rather than that requested rate, define the actual timing used by later replay.
 */
class ImuBatchSource(
    context: Context,
    private val onBatch: (ImuBatch) -> Unit,
) : SensorEventListener {
    private data class ActiveSensor(val sensor: Sensor, val name: String, val hasBias: Boolean)

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val activeSensors = listOfNotNull(
        select(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, Sensor.TYPE_GYROSCOPE, "gyroscope"),
        select(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER, "accelerometer"),
        select(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, Sensor.TYPE_MAGNETIC_FIELD, "magnetometer"),
    )
    private val sensorByType = activeSensors.associateBy { it.sensor.type }
    private val pending = mutableMapOf<Int, MutableList<ImuRawSample>>()
    private var running = false

    val sensorNames: List<String> get() = activeSensors.map { it.name }

    fun start(): StartResult {
        if (running) return StartResult.AlreadyRunning
        if (activeSensors.isEmpty()) return StartResult.Unavailable
        pending.clear()
        val registered = activeSensors.filter {
            sensorManager.registerListener(this, it.sensor, REQUESTED_PERIOD_MICROS, 0, handler)
        }
        if (registered.size != activeSensors.size) {
            sensorManager.unregisterListener(this)
            return StartResult.RegistrationFailed
        }
        running = true
        return StartResult.Started
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        pending.keys.toList().forEach(::flush)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val source = sensorByType[event.sensor.type] ?: return
        if (event.values.size < 3 || !event.values.take(3).all(Float::isFinite)) return
        val bias = if (source.hasBias && event.values.size >= 6 && event.values.slice(3..5).all(Float::isFinite)) {
            floatArrayOf(event.values[3], event.values[4], event.values[5])
        } else null
        val batch = pending.getOrPut(event.sensor.type) { mutableListOf() }
        if (batch.isNotEmpty() && event.timestamp - batch.first().sensorElapsedRealtimeNanos >= BATCH_DURATION_NANOS) {
            flush(event.sensor.type)
        }
        pending.getOrPut(event.sensor.type) { mutableListOf() }.add(
            ImuRawSample(event.timestamp, event.values[0], event.values[1], event.values[2],
                bias?.get(0), bias?.get(1), bias?.get(2), event.accuracy),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun flush(type: Int) {
        val samples = pending.remove(type)?.takeIf { it.isNotEmpty() } ?: return
        val source = sensorByType[type] ?: return
        onBatch(ImuBatch(source.name, source.sensor.type, REQUESTED_PERIOD_MICROS, samples.toList()))
    }

    private fun select(preferredType: Int, fallbackType: Int, name: String): ActiveSensor? {
        val preferred = sensorManager.getDefaultSensor(preferredType)
        if (preferred != null) return ActiveSensor(preferred, name, preferredType != fallbackType)
        val fallback = sensorManager.getDefaultSensor(fallbackType) ?: return null
        return ActiveSensor(fallback, name, false)
    }

    enum class StartResult { Started, AlreadyRunning, Unavailable, RegistrationFailed }
}
