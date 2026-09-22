package com.signalprofessor.qhub.eastwing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.signalprofessor.qhub.navigation.GnssLocationSource
import java.util.Locale
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

class MainActivity : ComponentActivity() {
    private lateinit var recorder: MissionRecorder
    private var lastState = MissionState("Ready")
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var replayButton: Button
    private lateinit var saveButton: Button
    private lateinit var uploadButton: Button
    private lateinit var tokenInput: EditText
    private var uploading = false

    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson"),
    ) { uri ->
        if (uri != null) {
            val message = runCatching { recorder.exportLatestMission(uri) }
                .fold(
                    onSuccess = { "Mission log saved ($it bytes)" },
                    onFailure = { "Save failed: ${it.message ?: "unknown error"}" },
                )
            render(lastState.copy(status = message))
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startMission() else render(MissionState("Location permission denied"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = MissionRecorder(this, ::render)
        setContentView(buildContent())
        render(MissionState("Ready"))
    }

    override fun onDestroy() {
        recorder.close()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "EastWing Qhub"
            textSize = 26f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        statusView = TextView(this).apply {
            textSize = 17f
            setPadding(0, padding, 0, padding)
        }
        content.addView(statusView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        startButton = Button(this).apply {
            text = "Start mission"
            setOnClickListener { requestStart() }
        }
        stopButton = Button(this).apply {
            text = "Stop mission"
            setOnClickListener { recorder.stop() }
        }
        replayButton = Button(this).apply {
            text = "Replay last mission"
            setOnClickListener {
                if (text == "Cancel replay") recorder.cancelReplay() else recorder.replayLast()
            }
        }
        saveButton = Button(this).apply {
            text = "Save last mission log"
            setOnClickListener { saveDocument.launch(recorder.latestMissionName() ?: "mission.ndjson") }
        }
        content.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(stopButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(replayButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(saveButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(TextView(this).apply {
            text = "Local backend test (USB only)"
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tokenInput = EditText(this).apply {
            hint = "Backend token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        content.addView(tokenInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        uploadButton = Button(this).apply {
            text = "Send last mission to Mac"
            setOnClickListener { uploadLastMission() }
        }
        content.addView(uploadButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return ScrollView(this).apply { addView(content) }
    }

    private fun requestStart() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) startMission() else permissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startMission() {
        when (recorder.start()) {
            GnssLocationSource.StartResult.Started -> Unit
            GnssLocationSource.StartResult.GpsDisabled -> render(MissionState("Enable GPS and try again"))
            GnssLocationSource.StartResult.PermissionMissing -> render(MissionState("Location permission missing"))
            GnssLocationSource.StartResult.AlreadyRunning -> render(MissionState("Mission or replay already running"))
        }
    }

    private fun uploadLastMission() {
        val file = runCatching { recorder.latestMissionForUpload() }.getOrElse {
            render(lastState.copy(status = "Upload unavailable: ${it.message}"))
            return
        }
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            render(lastState.copy(status = "Enter the backend token first"))
            return
        }
        uploading = true
        render(lastState.copy(status = "Sending mission to local backend..."))
        Thread {
            val result = runCatching { LocalTelemetryUploader.upload(file, token) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                uploading = false
                val message = result.fold(
                    onSuccess = { "Backend receipt: $it" },
                    onFailure = { "Upload failed: ${it.message ?: "unknown error"}" },
                )
                render(lastState.copy(status = message))
            }
        }.start()
    }

    private fun render(state: MissionState) {
        lastState = state
        val payload = state.latestEvent?.payload
        val latitude = payload?.get("latitudeDegrees")?.jsonPrimitive?.doubleOrNull
        val longitude = payload?.get("longitudeDegrees")?.jsonPrimitive?.doubleOrNull
        val accuracy = payload?.get("horizontalAccuracyMeters")?.jsonPrimitive?.doubleOrNull
        val position = if (latitude != null && longitude != null) {
            "%.6f, %.6f".format(Locale.US, latitude, longitude)
        } else "Waiting for GNSS"

        statusView.text = buildString {
            appendLine(state.status)
            appendLine()
            if (state.replayTotal > 0) appendLine("Replay progress: ${state.eventCount} / ${state.replayTotal}")
            else appendLine("Events: ${state.eventCount}")
            appendLine("Duration: ${state.elapsedMillis / 1000} s")
            appendLine("Log size: ${state.fileSizeBytes} bytes")
            appendLine("Position: $position")
            appendLine("Accuracy: ${accuracy?.let { "%.1f m".format(Locale.US, it) } ?: "-"}")
            state.verification?.let { appendLine("Verification: $it") }
            append("File: ${state.fileName ?: "-"}")
        }
        startButton.isEnabled = !state.isRecording && !state.isReplaying && !uploading
        stopButton.isEnabled = state.isRecording
        replayButton.isEnabled = !state.isRecording && !uploading
        replayButton.text = if (state.isReplaying) "Cancel replay" else "Replay last mission"
        saveButton.isEnabled = !state.isRecording && !state.isReplaying && !uploading
        uploadButton.isEnabled = !state.isRecording && !state.isReplaying && !uploading
    }
}
