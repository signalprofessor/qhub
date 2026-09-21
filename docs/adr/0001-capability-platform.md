# ADR 0001 Capability based Android platform

## Status

Accepted for the initial architecture.

## Context

The EastWing phone must support navigation, TERNAV, telemetry, camera streaming, and later AI and flight-controller integration. These functions have different sensors, rates, failure modes, and development schedules.

## Decision

Build one Android platform core with independently managed capability modules. The core owns lifecycle, time, configuration, event transport, logging, replay, and health reporting. Capabilities publish typed events and expose capability-specific controllers without depending directly on each other.

The first planned capabilities are navigation, TERNAV, telemetry, camera, and detection. ArduPilot integration is reserved for a later phase.

## Consequences

- Capabilities can be developed and tested incrementally.
- Logging, replay, and backend messages share common event definitions.
- Camera, navigation, and telemetry failures can be isolated and reported separately.
- The core contracts must remain generic and must not acquire camera-specific or sensor-specific methods.
