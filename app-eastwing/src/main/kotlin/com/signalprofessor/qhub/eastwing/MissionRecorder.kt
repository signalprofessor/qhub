package com.signalprofessor.qhub.eastwing

import android.content.Context
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
    private val missionsDirectory = File(appContext.filesDir, "missions")
    private val deviceId: String = appContext.getSharedPreferences("qhub_identity", Context.MODE_PRIVATE)
        .let { preferences ->
            preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("device_id", it).apply()
            }
        }

    private var locationSource: GnssLocationSource? = null
    private var writer: NdjsonEventWriter? = null
    private var factory: EventFactory? = null
    private var eventCount = 0L
    private var startedAtUtcMillis = 0L
    private var currentFile: File? = null

    fun start(): GnssLocationSource.StartResult {
        if (writer != null) return GnssLocationSource.StartResult.AlreadyRunning
        val sessionId = UUID.randomUUID().toString()
        val name = "mission_${fileTimestamp.format(Date())}_$sessionId.ndjson"
        val file = File(missionsDirectory, name)
        val pendingWriter = NdjsonEventWriter(file)
        val pendingFactory = EventFactory(deviceId, sessionId, AndroidQhubClock)
        val pendingSource = GnssLocationSource(appContext, ::recordSample)

        writer = pendingWriter
        factory = pendingFactory
        locationSource = pendingSource
        eventCount = 0
        startedAtUtcMillis = System.currentTimeMillis()
        currentFile = file

        val result = pendingSource.start()
        if (result != GnssLocationSource.StartResult.Started) {
            pendingWriter.close()
            file.delete()
            clearActiveSession()
            return result
        }
        publishState("Recording GNSS", null)
        return result
    }

    fun stop() {
        locationSource?.stop()
        writer?.close()
        val lastEventCount = eventCount
        val lastFile = currentFile
        clearActiveSession()
        onState(
            MissionState(
                status = "Mission stopped",
                eventCount = lastEventCount,
                fileSizeBytes = lastFile?.length() ?: 0,
                fileName = lastFile?.name,
            ),
        )
    }

    fun replayLast() {
        val file = latestMissionFile()
        if (file == null) {
            onState(MissionState(status = "No recorded mission found"))
            return
        }
        val events = NdjsonEventReader.read(file)
        var latest: EventEnvelope? = null
        EventReplay(events).replay { latest = it }
        onState(
            MissionState(
                status = "Replay complete",
                eventCount = events.size.toLong(),
                fileSizeBytes = file.length(),
                fileName = file.name,
                latestEvent = latest,
            ),
        )
    }

    fun close() {
        if (writer != null) stop()
    }

    private fun recordSample(sample: GnssSample) {
        val event = factory?.create(
            source = NAVIGATION_CAPABILITY_ID,
            eventType = GNSS_EVENT_TYPE,
            payload = sample.toEventPayload(),
        ) ?: return
        writer?.append(event)
        eventCount += 1
        publishState("Recording GNSS", event)
    }

    private fun publishState(status: String, event: EventEnvelope?) {
        onState(
            MissionState(
                status = status,
                eventCount = eventCount,
                fileSizeBytes = writer?.sizeBytes() ?: currentFile?.length() ?: 0,
                elapsedMillis = (System.currentTimeMillis() - startedAtUtcMillis).coerceAtLeast(0),
                fileName = currentFile?.name,
                latestEvent = event,
            ),
        )
    }

    private fun latestMissionFile(): File? = missionsDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "ndjson" }
        ?.maxByOrNull(File::lastModified)

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
)
