package com.signalprofessor.qhub.eastwing

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.signalprofessor.qhub.core.capability.CapabilityId
import com.signalprofessor.qhub.core.event.EventEnvelope
import com.signalprofessor.qhub.core.event.EventFactory
import com.signalprofessor.qhub.log.EventReplay
import com.signalprofessor.qhub.log.NdjsonEventReader
import com.signalprofessor.qhub.log.NdjsonEventWriter
import com.signalprofessor.qhub.navigation.GNSS_EVENT_TYPE
import com.signalprofessor.qhub.navigation.GnssLocationSource
import com.signalprofessor.qhub.navigation.GnssSample
import com.signalprofessor.qhub.navigation.PRESSURE_EVENT_TYPE
import com.signalprofessor.qhub.navigation.PressureSample
import com.signalprofessor.qhub.navigation.PressureSensorSource
import com.signalprofessor.qhub.navigation.ROTATION_VECTOR_EVENT_TYPE
import com.signalprofessor.qhub.navigation.RotationVectorSample
import com.signalprofessor.qhub.navigation.RotationVectorSource
import com.signalprofessor.qhub.navigation.toEventPayload
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MissionRecorder(
    context: Context,
    private val onState: (MissionState) -> Unit,
    private val onRecordedEvent: (EventEnvelope) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val missionsDirectory = File(appContext.filesDir, "missions")
    private val identityPreferences = appContext.getSharedPreferences("qhub_identity", Context.MODE_PRIVATE)
    private val summaryPreferences = appContext.getSharedPreferences("qhub_mission_summaries", Context.MODE_PRIVATE)
    private val deviceId: String = identityPreferences.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { identityPreferences.edit().putString("device_id", it).apply() }

    private var locationSource: GnssLocationSource? = null
    private var pressureSource: PressureSensorSource? = null
    private var rotationSource: RotationVectorSource? = null
    private var pressureAvailable: Boolean? = null
    private var latestGnssEvent: EventEnvelope? = null
    private var latestPressureEvent: EventEnvelope? = null
    private var writer: NdjsonEventWriter? = null
    private var factory: EventFactory? = null
    private var eventCount = 0L
    private var lastRecordedEvent: EventEnvelope? = null
    private var startedAtUtcMillis = 0L
    private var currentFile: File? = null
    private var activeReplay: EventReplay? = null
    private var replayStep: Runnable? = null

    fun start(): GnssLocationSource.StartResult {
        if (writer != null || activeReplay != null) return GnssLocationSource.StartResult.AlreadyRunning
        val sessionId = UUID.randomUUID().toString()
        val name = "mission_${fileTimestamp.format(Date())}_$sessionId.ndjson"
        val file = File(missionsDirectory, name)
        val pendingWriter = NdjsonEventWriter(file)
        val pendingSource = GnssLocationSource(appContext, ::recordSample)
        val pendingPressureSource = PressureSensorSource(appContext, ::recordPressure)
        val pendingRotationSource = RotationVectorSource(appContext, ::recordRotation)

        writer = pendingWriter
        factory = EventFactory(deviceId, sessionId, AndroidQhubClock)
        locationSource = pendingSource
        eventCount = 0
        lastRecordedEvent = null
        latestGnssEvent = null
        latestPressureEvent = null
        pressureAvailable = null
        startedAtUtcMillis = System.currentTimeMillis()
        currentFile = file

        val result = pendingSource.start()
        if (result != GnssLocationSource.StartResult.Started) {
            pendingWriter.close()
            file.delete()
            clearActiveSession()
            return result
        }
        pressureAvailable = pendingPressureSource.start() == PressureSensorSource.StartResult.Started
        if (pressureAvailable == true) pressureSource = pendingPressureSource
        if (pendingRotationSource.start() == RotationVectorSource.StartResult.Started) rotationSource = pendingRotationSource
        publishRecordingState(null)
        return result
    }

    fun stop() {
        locationSource?.stop()
        pressureSource?.stop()
        rotationSource?.stop()
        writer?.close()
        val completedFile = currentFile
        if (completedFile != null) {
            summaryPreferences.edit()
                .putLong("${completedFile.name}.count", eventCount)
                .putString("${completedFile.name}.lastEventId", lastRecordedEvent?.eventId.orEmpty())
                .apply()
        }
        val finalState = MissionState(
            status = "Mission stopped",
            eventCount = eventCount,
            fileSizeBytes = completedFile?.length() ?: 0,
            fileName = completedFile?.name,
            latestEvent = lastRecordedEvent,
            latestGnssEvent = latestGnssEvent,
            latestPressureEvent = latestPressureEvent,
            pressureAvailable = pressureAvailable,
        )
        clearActiveSession()
        onState(finalState)
    }

    fun replayLast() {
        if (writer != null || activeReplay != null) return
        val file = latestMissionFile()
        if (file == null) {
            onState(MissionState(status = "No recorded mission found"))
            return
        }
        val events = runCatching { NdjsonEventReader.read(file) }.getOrElse { error ->
            onState(MissionState(status = "Replay failed: ${error.message ?: "invalid log"}"))
            return
        }
        if (events.isEmpty()) {
            onState(MissionState(status = "This mission has no GNSS events", fileName = file.name))
            return
        }
        val replay = EventReplay(events)
        activeReplay = replay
        latestGnssEvent = null
        latestPressureEvent = null
        pressureAvailable = null

        fun scheduleNext() {
            val step = Runnable {
                if (activeReplay !== replay) return@Runnable
                val event = replay.next() ?: return@Runnable
                val complete = replay.position == replay.size
                if (event.eventType == GNSS_EVENT_TYPE) latestGnssEvent = event
                if (event.eventType == PRESSURE_EVENT_TYPE) latestPressureEvent = event
                val verification = if (complete) verifyReplay(file, replay.position.toLong(), event) else null
                onState(
                    MissionState(
                        status = if (complete) "Replay complete" else "Replaying at recorded speed",
                        eventCount = replay.position.toLong(),
                        fileSizeBytes = file.length(),
                        elapsedMillis = (event.timestamp.monotonicNanos - events.first().timestamp.monotonicNanos)
                            .coerceAtLeast(0) / 1_000_000,
                        fileName = file.name,
                        latestEvent = event,
                        latestGnssEvent = latestGnssEvent,
                        latestPressureEvent = latestPressureEvent,
                        replayTotal = replay.size,
                        verification = verification,
                        isReplaying = !complete,
                    ),
                )
                if (complete) {
                    activeReplay = null
                    replayStep = null
                } else {
                    scheduleNext()
                }
            }
            replayStep = step
            mainHandler.postDelayed(step, replay.delayUntilNextMillis())
        }

        onState(MissionState(status = "Starting replay", fileName = file.name, replayTotal = replay.size, isReplaying = true))
        scheduleNext()
    }

    fun cancelReplay() {
        replayStep?.let(mainHandler::removeCallbacks)
        replayStep = null
        activeReplay = null
        onState(MissionState(status = "Replay cancelled"))
    }

    /** Copies a completed mission to a document chosen by the user. No network transfer occurs. */
    fun exportLatestMission(destination: Uri): Long {
        check(writer == null && activeReplay == null) { "Stop the mission or replay before saving its log" }
        val source = latestMissionFile() ?: error("No recorded mission found")
        val output = appContext.contentResolver.openOutputStream(destination, "w")
            ?: error("Could not open the selected document")
        val copiedBytes = source.inputStream().use { input -> output.use(input::copyTo) }
        check(copiedBytes == source.length()) { "Saved log size differs from the original" }
        return copiedBytes
    }

    fun close() {
        replayStep?.let(mainHandler::removeCallbacks)
        replayStep = null
        activeReplay = null
        if (writer != null) stop()
    }

    private fun verifyReplay(file: File, replayedCount: Long, finalEvent: EventEnvelope): String {
        val expectedCount = summaryPreferences.getLong("${file.name}.count", -1)
        val expectedLastId = summaryPreferences.getString("${file.name}.lastEventId", null)
        if (expectedCount < 0 || expectedLastId == null) return "No stop-time summary for this older recording"
        return if (expectedCount == replayedCount && expectedLastId == finalEvent.eventId) {
            "MATCH: count and final event agree with recording"
        } else {
            "MISMATCH: replay differs from recording summary"
        }
    }

    private fun recordSample(sample: GnssSample) {
        val event = factory?.create(
            source = NAVIGATION_CAPABILITY_ID,
            eventType = GNSS_EVENT_TYPE,
            payload = sample.toEventPayload(),
        ) ?: return
        writer?.append(event)
        eventCount += 1
        lastRecordedEvent = event
        latestGnssEvent = event
        onRecordedEvent(event)
        publishRecordingState(event)
    }

    private fun recordPressure(sample: PressureSample) {
        val event = factory?.create(
            source = NAVIGATION_CAPABILITY_ID,
            eventType = PRESSURE_EVENT_TYPE,
            payload = sample.toEventPayload(),
        ) ?: return
        writer?.append(event)
        eventCount += 1
        lastRecordedEvent = event
        latestPressureEvent = event
        onRecordedEvent(event)
        publishRecordingState(event)
    }

    private fun recordRotation(sample: RotationVectorSample) {
        val event = factory?.create(
            source = NAVIGATION_CAPABILITY_ID,
            eventType = ROTATION_VECTOR_EVENT_TYPE,
            payload = sample.toEventPayload(),
        ) ?: return
        writer?.append(event)
        eventCount += 1
        lastRecordedEvent = event
        onRecordedEvent(event)
        publishRecordingState(event)
    }

    private fun publishRecordingState(event: EventEnvelope?) {
        onState(
            MissionState(
                status = if (pressureAvailable == true) "Recording GNSS + barometer"
                    else "Recording GNSS (barometer unavailable)",
                eventCount = eventCount,
                fileSizeBytes = writer?.sizeBytes() ?: 0,
                elapsedMillis = (System.currentTimeMillis() - startedAtUtcMillis).coerceAtLeast(0),
                fileName = currentFile?.name,
                latestEvent = event,
                latestGnssEvent = latestGnssEvent,
                latestPressureEvent = latestPressureEvent,
                pressureAvailable = pressureAvailable,
                isRecording = true,
            ),
        )
    }

    private fun latestMissionFile(): File? = missionsDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "ndjson" }
        ?.maxByOrNull(File::lastModified)

    fun latestMissionName(): String? = latestMissionFile()?.name

    fun latestMissionForUpload(): File {
        check(writer == null && activeReplay == null) { "Stop the mission or replay first" }
        return latestMissionFile() ?: error("No recorded mission found")
    }

    private fun clearActiveSession() {
        locationSource = null
        pressureSource = null
        rotationSource = null
        writer = null
        factory = null
        currentFile = null
        startedAtUtcMillis = 0
    }

    companion object {
        private val NAVIGATION_CAPABILITY_ID = CapabilityId("navigation")
        private val fileTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}

data class MissionState(
    val status: String,
    val eventCount: Long = 0,
    val fileSizeBytes: Long = 0,
    val elapsedMillis: Long = 0,
    val fileName: String? = null,
    val latestEvent: EventEnvelope? = null,
    val latestGnssEvent: EventEnvelope? = null,
    val latestPressureEvent: EventEnvelope? = null,
    val pressureAvailable: Boolean? = null,
    val replayTotal: Int = 0,
    val verification: String? = null,
    val isRecording: Boolean = false,
    val isReplaying: Boolean = false,
)
