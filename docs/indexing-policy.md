# Indexing Policy

MVP 5 defines indexing queue contracts and policy models only. It does not add a background scheduler.

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

## Manual Commands

Manual indexing command models support:

- index now
- index one connector
- index one date range, optionally scoped to one connector

## UI Status

`IndexingStatus` exposes queue depth, running connector IDs, last completion time, automatic policy, manual command availability, and visible queue items.

Status text must describe indexing state without logging or retaining source originals.
