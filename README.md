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

## Current foundation

- `platform-core`: platform-neutral event and capability contracts
- `app-eastwing`: minimal Android host application
- dual-clock event timestamps, identity, schema versioning, and sequence numbers
- tested capability lifecycle behavior

No source code from the Qulinda Qhub repository has been copied.

## Build

Use Android Studio's bundled JDK and an installed Android SDK:

```bash
./gradlew test :app-eastwing:assembleDebug
```
