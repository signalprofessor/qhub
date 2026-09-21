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
- `capabilities/navigation`: Pixel GNSS acquisition and raw GNSS event payloads
- `app-eastwing`: mission start, stop, status, and replay UI
- dual-clock timestamps, identity, schema versioning, and sequence numbers
- tested event round trips and capability lifecycle behavior

No source code from the Qulinda Qhub repository has been copied.

## Pixel test

1. Install the debug APK and grant precise location permission.
2. Go outdoors with GPS enabled.
3. Press **Start mission** and wait for the event count and position to update.
4. Walk or drive a short route, then press **Stop mission**.
5. Press **Replay last mission**. The replay count, final position, and file information should match the recording.

Mission logs are stored in the app-private `files/missions` directory.

## Build

Use Android Studio's bundled JDK and an installed Android SDK:

```bash
./gradlew clean test :app-eastwing:assembleDebug
./gradlew :app-eastwing:lintDebug
```
