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
import com.signalprofessor.qhub.navigation.toEventPayload
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MissionRecorder(
    context: Context,
    private val onState: (MissionState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val missionsDirectory = File(appContext.filesDir, "missions")
    private val identityPreferences = appContext.getSharedPreferences("qhub_identity", Context.MODE_PRIVATE)
    private val summaryPreferences = appContext.getSharedPreferences("qhub_mission_summaries", Context.MODE_PRIVATE)
    private val deviceId: String = identityPreferences.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { identityPreferences.edit().putString("device_id", it).apply() }

    private var locationSource: GnssLocationSource? = null
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

        writer = pendingWriter
        factory = EventFactory(deviceId, sessionId, AndroidQhubClock)
        locationSource = pendingSource
        eventCount = 0
        lastRecordedEvent = null
        startedAtUtcMillis = System.currentTimeMillis()
        currentFile = file

        val result = pendingSource.start()
        if (result != GnssLocationSource.StartResult.Started) {
            pendingWriter.close()
            file.delete()
            clearActiveSession()
            return result
        }
        publishRecordingState(null)
        return result
    }

    fun stop() {
        locationSource?.stop()
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

        fun scheduleNext() {
            val step = Runnable {
                if (activeReplay !== replay) return@Runnable
                val event = replay.next() ?: return@Runnable
                val complete = replay.position == replay.size
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
        publishRecordingState(event)
    }

    private fun publishRecordingState(event: EventEnvelope?) {
        onState(
            MissionState(
                status = "Recording GNSS",
                eventCount = eventCount,
                fileSizeBytes = writer?.sizeBytes() ?: 0,
                elapsedMillis = (System.currentTimeMillis() - startedAtUtcMillis).coerceAtLeast(0),
                fileName = currentFile?.name,
                latestEvent = event,
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
    val replayTotal: Int = 0,
    val verification: String? = null,
    val isRecording: Boolean = false,
    val isReplaying: Boolean = false,
)
