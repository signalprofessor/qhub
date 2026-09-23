# Qhub

Qhub is a modular Android platform for the EastWing drone mission system. The first target is a Pixel 8 running navigation, terrain-referenced navigation, telemetry, and rear-camera FPV without depending on a flight controller.

## Initial delivery sequence

1. Core lifecycle, event model, logging, and replay
2. Navigation and backend telemetry
3. Position-only terrain-navigation particle filter using GNSS speed and heading
4. Rear-camera FPV and an approximate ground footprint
5. Reduced-rate Animal, Person, Vehicle inference
6. Extended particle-filter motion model
7. Pixhawk and ArduPilot integration when hardware is available

## Repository policy

- `eprotection/qhub` is read-only reference material.
- Code is imported only after its provenance and permission are recorded in `PROVENANCE.md`.
- Credentials, signing material, production endpoints, and customer data must never be committed.
- New capabilities should depend on shared contracts, not directly on one another.

## Current implementation

- `platform-core`: platform-neutral event and capability contracts
- `event-log`: append-only NDJSON logging and deterministic replay
- `capabilities/navigation`: Pixel GNSS, barometer, and rotation-vector acquisition with raw, timestamped event payloads
- `app-eastwing`: mission recording, replay, export, and opt-in local telemetry UI
- `backend`: token-protected HTTP ingestion, SQLite storage, and local read-only telemetry dashboard
- dual-clock timestamps, identity, schema versioning, and sequence numbers
- tested event round trips and capability lifecycle behavior

No source code from the Qulinda Qhub repository has been copied.

## Pixel test

1. Install the debug APK and grant precise location permission.
2. Go outdoors with GPS enabled.
3. Press **Start mission** and wait for the event count, position, and pressure reading to update. If the device has no pressure sensor, the app says so; GNSS recording still works.
4. Walk or drive a short route, then press **Stop mission**.
5. Press **Replay last mission**. The replay count, final position, and file information should match the recording.
6. Press **Save last mission log** and choose a location, such as Downloads, to export the raw NDJSON file for inspection. This is a local copy, not a backend upload.

Mission logs are stored in the app-private `files/missions` directory. Starting a mission now starts a foreground location service with a persistent notification and a **Stop mission** action. A partial CPU wake lock is held only while recording (with a four-hour safety limit) to keep the non-wake-up pressure sensor active when the screen is off. Stop the mission when finished to conserve battery. The service is started from the visible app after location permission is granted; it does not require background-location permission. Live telemetry remains opt-in and foreground-only, so a completed log may still need a manual upload over USB.

## Build

Use Android Studio's bundled JDK and an installed Android SDK:

```bash
./gradlew clean test :app-eastwing:assembleDebug
./gradlew :app-eastwing:lintDebug
```

Rotation-vector events (`navigation.rotation_vector`) store the Android fused sensor quaternion components `x`, `y`, `z`, optional `w`, optional heading accuracy in radians, sensor accuracy, and elapsed-realtime sensor timestamp at about 1 Hz. Values use Android device coordinates; no phone-to-vehicle mounting alignment or magnetic/true-north correction is assumed.

Raw IMU events (`navigation.imu_batch`) preserve approximately one second of samples per event. The app requests 100 Hz for the accelerometer, uncalibrated gyroscope, and uncalibrated magnetometer, falling back to calibrated gyro or magnetometer only when necessary. Every sample retains its own Android elapsed-realtime timestamp, XYZ values, optional Android-reported bias, and accuracy status; actual sample rate must be derived from timestamps. The app does not integrate attitude or discard raw samples. Quaternion integration and gyro-bias experiments are deterministic replay products in the backend.
