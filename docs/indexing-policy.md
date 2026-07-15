# Indexing Policy

Grayin persists connector-level indexing tasks in the same SQLCipher database as derived memory. Manual source actions now enqueue and execute through one shared command executor. WorkManager scheduling is the next runtime layer; automatic settings alone do not imply that background work has run.

## Processing States

The durable indexing queue uses these states:

- pending
- running
- completed
- skipped
- failed

Claiming is an atomic `PENDING -> RUNNING` transaction with a worker lease and attempt count. Claims are filtered by manual or automatic trigger, so a background worker cannot consume a foreground/manual task. A terminal acknowledgement must match both the current lease owner and attempt number, so a stale worker cannot complete newer reclaimed work. Expired leases are requeued below the attempt limit and become a stable failure code at the limit. Completed, skipped, and failed states are terminal. Automatic tasks are unique per low-usage-window key and connector.

Queue rows contain only connector ID, trigger, requested date range, timestamps, state, lease metadata, stable reason codes, and derived output counts. They never contain source content, connector output, exception text, URIs, evidence, prompts, or answers. Old terminal rows are bounded by age and count pruning.

## Automatic Indexing

Automatic indexing is allowed only when the policy and current device conditions agree:

- device is charging when charging is required
- current local time is inside the configured low-usage window
- battery percentage is at or above the configured minimum
- thermal state is unknown, nominal, or warm when thermal gating is enabled

Hot or critical thermal states block automatic indexing. Invalid time windows, unknown or low battery, missing charging state, and thermal blocks return stable reason enums rather than exception messages.

The Sources page lets the user persist automatic indexing preferences:

- automatic indexing enabled or disabled
- charging-only requirement
- low-usage start and end hour

The background scheduler must read these preferences and re-evaluate live device conditions on every run. Manual indexing remains available even when automatic indexing is off.

## Connector Execution Modes

- Calendar, Photos, App Usage, and Local Files are background-scannable after consent.
- Location is foreground-only because Grayin does not request background-location permission.
- Notifications are event-driven and are derived on allowed arrivals; no raw notification is retained for scheduled replay.

## Manual Commands

Manual indexing command models support:

- index all currently eligible connectors
- index one connector
- index one date range, optionally scoped to one connector

The shared executor expands commands into connector tasks, atomically claims only the requested trigger, rechecks connector mode, consent, source enablement, and permission, then scans and commits derived output. The executor and SQLCipher store both require the scan connector ID to match the claimed task. The store checks that identity plus the live item ID, lease owner, attempt number, and lease expiry in the same transaction that writes the derived rows and marks the task complete. A reclaimed or expired worker therefore cannot write stale scan output. Manual expansion excludes event-driven notifications. Automatic expansion includes only background-scannable connectors; Location remains manual foreground-only.

Connector scan and store exceptions become stable failure codes without exception text. Missing permission, disabled source, ineligible mode, and no indexable output become stable skip reasons. Coroutine cancellation is rethrown and the running lease is left for bounded recovery. After the encrypted derived-memory commit and queue completion, connector UI checkpoint updates are best-effort; a checkpoint exception does not make committed data appear failed.

## UI Status

The encrypted queue exposes queue depth, running connector IDs, last completion time, recent task states, stable skipped/failure reasons, and indexed-event counts. A separate singleton runtime row records the last automatic check/run outcome without source data.

Status text must describe indexing state without logging or retaining source originals.
