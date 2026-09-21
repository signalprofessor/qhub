# ADR 0003 NDJSON black box log

## Status

Accepted for the first mission-recording slice.

## Decision

The canonical local black-box log is append-only newline-delimited JSON. Each line contains one complete common event envelope. CSV will later be provided as an analysis export rather than the canonical mixed-event store.

Replay reads the stored envelopes without changing their identity, sequence number, timestamps, event type, or payload.

## Consequences

- A partially written final line cannot corrupt earlier events.
- Different sensor and estimator payloads can coexist in chronological order.
- Logs remain inspectable with ordinary text tools.
- Schema evolution is explicit.
- High-rate binary payloads such as video are referenced rather than embedded.
