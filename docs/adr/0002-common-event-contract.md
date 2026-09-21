# ADR 0002 Common event contract

## Status

Accepted for the initial implementation.

## Decision

Every logged, replayed, or transmitted event uses one envelope containing:

- schema version
- unique event identifier
- device and mission-session identifiers
- session-local sequence number
- source capability and event type
- monotonic Android time for ordering and estimation
- UTC time for backend display and correlation
- capability-owned payload

Serialization and storage formats will be added separately so the domain contract does not depend on CSV, JSON, MQTT, or a database.

## Consequences

- Live and replay processing can share the same event semantics.
- Missing and duplicated messages can be detected by sequence number.
- Estimators do not depend on adjustments to wall-clock time.
- Wire-format compatibility is controlled explicitly through the schema version.
