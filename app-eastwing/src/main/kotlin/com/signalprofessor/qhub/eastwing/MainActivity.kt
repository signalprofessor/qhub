package com.signalprofessor.qhub.eastwing

import android.Manifest
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.signalprofessor.qhub.navigation.GnssLocationSource
import java.util.Locale
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

class MainActivity : ComponentActivity() {
    private lateinit var recorder: MissionRecorder
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

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
            isEnabled = false
            setOnClickListener { recorder.stop() }
        }
        val replayButton = Button(this).apply {
            text = "Replay last mission"
            setOnClickListener { recorder.replayLast() }
        }
        content.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(stopButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(replayButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return ScrollView(this).apply { addView(content) }
    }

    private fun requestStart() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startMission()
        } else {
            permissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startMission() {
        when (recorder.start()) {
            GnssLocationSource.StartResult.Started -> {
                startButton.isEnabled = false
                stopButton.isEnabled = true
            }
            GnssLocationSource.StartResult.GpsDisabled -> render(MissionState("Enable GPS and try again"))
            GnssLocationSource.StartResult.PermissionMissing -> render(MissionState("Location permission missing"))
            GnssLocationSource.StartResult.AlreadyRunning -> render(MissionState("Mission already running"))
        }
    }

    private fun render(state: MissionState) {
        val payload = state.latestEvent?.payload
        val latitude = payload?.get("latitudeDegrees")?.jsonPrimitive?.doubleOrNull
        val longitude = payload?.get("longitudeDegrees")?.jsonPrimitive?.doubleOrNull
        val accuracy = payload?.get("horizontalAccuracyMeters")?.jsonPrimitive?.doubleOrNull
        val position = if (latitude != null && longitude != null) {
            "%.6f, %.6f".format(Locale.US, latitude, longitude)
        } else {
            "Waiting for GNSS"
        }
        statusView.text = buildString {
            appendLine(state.status)
            appendLine()
            appendLine("Events: ${state.eventCount}")
            appendLine("Duration: ${state.elapsedMillis / 1000} s")
            appendLine("Log size: ${state.fileSizeBytes} bytes")
            appendLine("Position: $position")
            appendLine("Accuracy: ${accuracy?.let { "%.1f m".format(Locale.US, it) } ?: "-"}")
            append("File: ${state.fileName ?: "-"}")
        }
        val recording = state.status == "Recording GNSS"
        startButton.isEnabled = !recording
        stopButton.isEnabled = recording
    }
}
