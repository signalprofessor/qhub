package com.signalprofessor.qhub.eastwing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.signalprofessor.qhub.navigation.GnssLocationSource

/** User-started foreground recording. No network connection is required. */
class MissionRecordingService : Service() {
    private lateinit var runtime: MissionRuntime
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        runtime = MissionRuntime.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mission recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopMission()
            ACTION_START -> startMission()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startMission() {
        if (foreground) return
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EastWing:MissionRecording")
                .apply { setReferenceCounted(false); acquire(MAX_WAKE_MILLIS) }
            when (runtime.recorder.start()) {
                GnssLocationSource.StartResult.Started -> Unit
                GnssLocationSource.StartResult.GpsDisabled -> failStart("Enable GPS and try again")
                GnssLocationSource.StartResult.PermissionMissing -> failStart("Location permission missing")
                GnssLocationSource.StartResult.AlreadyRunning -> failStart("Mission or replay already running")
            }
        } catch (error: Exception) {
            failStart("Could not start background recording: ${error.message ?: "unknown error"}")
        }
    }

    private fun failStart(message: String) {
        runtime.publishState(MissionState(message))
        stopForegroundRecording()
        stopSelf()
    }

    private fun stopMission() {
        try {
            runtime.recorder.stop()
        } finally {
            stopForegroundRecording()
            stopSelf()
        }
    }

    private fun stopForegroundRecording() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    override fun onDestroy() {
        try {
            if (runtime.lastState.isRecording) runtime.recorder.close()
        } finally {
            stopForegroundRecording()
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MissionRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("EastWing mission recording")
            .setContentText("GNSS, pressure, and raw IMU · tap to open")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop mission", stop,
            ).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.signalprofessor.qhub.eastwing.START_MISSION"
        const val ACTION_STOP = "com.signalprofessor.qhub.eastwing.STOP_MISSION"
        private const val CHANNEL_ID = "eastwing_mission_recording"
        private const val NOTIFICATION_ID = 101
        private const val MAX_WAKE_MILLIS = 4 * 60 * 60 * 1000L
    }
}
