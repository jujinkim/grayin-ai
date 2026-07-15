# Indexing Policy

Grayin persists connector-level indexing tasks in the same SQLCipher database as derived memory. Manual source actions and the WorkManager runtime enqueue and execute through one shared command executor.

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

When automatic indexing is enabled, Grayin registers one unique periodic WorkManager request with a one-hour interval and 15-minute flex. It requires battery-not-low and storage-not-low, adds WorkManager's charging constraint when charging-only is selected, and deliberately has no network constraint because scheduled connectors are local-only. Preference changes launch reconciliation in a process-level scope, so closing the Sources screen or Activity does not abandon an opt-out. Disabling first commits a disabled control generation and atomically fences pending/running automatic tasks as skipped, then waits for WorkManager cancellation. App startup re-applies persisted control and schedule state; expired leases are recovered by the next executor run.

Every worker run reloads the preference and re-evaluates the exact low-usage window, charging state, battery percentage, and thermal state. WorkManager timing is best-effort, so the runtime check—not the periodic interval—is authoritative. A deterministic key identifies one local low-usage window; the post-midnight portion of a cross-midnight window uses the date on which that window started. Manual indexing remains available even when automatic indexing is off.

Automatic settings are represented by a durable SQLCipher control generation. A settings change advances the generation and administratively skips older pending/running work. Automatic enqueue, claim, derived-scan commit, and runtime-status writes all require the currently enabled generation. This prevents a canceled or blocking old worker from publishing after disable/reconfiguration, while a later generation can use the same low-usage-window key without colliding with terminal history.

## Connector Execution Modes

- Calendar, Photos, App Usage, and Local Files are background-scannable after consent.
- Location is foreground-only because Grayin does not request background-location permission.
- Notifications are event-driven and are derived on allowed arrivals; no raw notification is retained for scheduled replay.

## Manual Commands

Manual indexing command models support:

- index all currently eligible connectors
- index one connector
- index one date range, optionally scoped to one connector

The shared executor expands commands into connector tasks, atomically claims only the requested trigger, rechecks connector mode, consent, source enablement, and permission, then scans and commits derived output. Connector readiness and scan calls have a timeout shorter than the 15-minute lease, while connector implementations also cap each scan's item/byte work. The executor and SQLCipher store both require the scan connector ID to match the claimed task. The store checks that identity plus the live item ID, lease owner, attempt number, and lease expiry in the same transaction that writes the derived rows and marks the task complete. Metadata-only completed/skipped/failed transitions use the same live-expiry fence. A reclaimed, canceled, timed-out, or expired worker therefore cannot write stale output or acknowledge an expired lease. Manual expansion excludes event-driven notifications. Automatic expansion includes only background-scannable connectors; Location remains manual foreground-only.

Connector scan, timeout, and store exceptions become stable failure codes without exception text. Missing permission, disabled source, ineligible mode, and no indexable output become stable skip reasons. Coroutine cancellation is checked again after potentially blocking connector calls, rethrown, and the running lease is left for bounded recovery. A normal WorkManager stop records a stable stopped outcome instead of an internal error. After the encrypted derived-memory commit and queue completion, connector UI checkpoint updates are best-effort; a checkpoint exception does not make committed data appear failed.

## UI Status

The encrypted queue exposes combined manual/automatic queue depth, only unexpired-lease running connector IDs, last completion time, bounded recent task states, stable skipped/failure reasons, trigger, and indexed-event counts. An expired persisted running lease is presented as recovery pending until the executor requeues it, so it is never shown as live work. A separate singleton runtime row records the latest automatic control or worker activity, outcome, stable reason, and derived-event count without source data. Because a settings change is itself an automatic activity and queue/runtime reads are separate transactions, the UI does not label this singleton as an atomic "last run." Recent task rows preserve actual connector execution outcomes.

Sources loads this status on entry and refreshes only the bounded queue/runtime view every two seconds while the Sources screen and Activity lifecycle are visible. Read failures preserve the last good snapshot and remain retryable on the next interval. The poll does not load connector state, derived events, model status, exception text, or source data. Internal task IDs and lease owners may be materialized by the bounded queue read but are discarded by the UI mapper and never presented. All outcomes and stable reasons are mapped explicitly into English, Korean, and Japanese. One concise dynamic summary is the polite accessibility live region; detailed history remains normally navigable and is not repeatedly announced as a single block.

Status text must describe indexing state without logging or retaining source originals.
