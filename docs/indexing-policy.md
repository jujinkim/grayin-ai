# Indexing Policy

MVP defines indexing queue contracts, policy models, manual source indexing controls, and persisted automatic indexing settings. It does not add a background scheduler.

## Processing States

Indexing work uses `ProcessingState`:

- pending
- running
- completed
- failed
- skipped
- stale

## Automatic Indexing

Automatic indexing is allowed only when the policy and current device conditions agree:

- device is charging when charging is required
- current local time is inside the configured low-usage window
- battery percentage is at or above the configured minimum
- thermal state is unknown, nominal, or warm when thermal gating is enabled

Hot or critical thermal states block automatic indexing.

The Sources page lets the user persist automatic indexing preferences:

- automatic indexing enabled or disabled
- charging-only requirement
- low-usage start and end hour

Future background schedulers must read these preferences before indexing. Manual indexing is still available even when automatic indexing is off.

## Manual Commands

Manual indexing command models support:

- index now
- index all enabled sources
- index one connector
- index one date range, optionally scoped to one connector

## UI Status

`IndexingStatus` exposes queue depth, running connector IDs, last completion time, automatic policy, manual command availability, and visible queue items.

Status text must describe indexing state without logging or retaining source originals.
