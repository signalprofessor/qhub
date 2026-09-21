package com.signalprofessor.qhub.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

class GnssLocationSource(
    context: Context,
    private val onSample: (GnssSample) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val appContext = context.applicationContext
    private val listener = LocationListener { location -> onSample(location.toGnssSample()) }
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(): StartResult {
        if (running) return StartResult.AlreadyRunning
        if (
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.PermissionMissing
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return StartResult.GpsDisabled
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1_000L,
            0f,
            listener,
            Looper.getMainLooper(),
        )
        running = true
        return StartResult.Started
    }

    fun stop() {
        if (!running) return
        locationManager.removeUpdates(listener)
        running = false
    }

    enum class StartResult {
        Started,
        AlreadyRunning,
        PermissionMissing,
        GpsDisabled,
    }
}
