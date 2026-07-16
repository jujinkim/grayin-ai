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

Connector metadata explicitly declares date-range support. Calendar, Photos, and App Usage are the only current supporting connectors. A connector-specific unsupported range is rejected before enqueue; an unscoped range expands only to enabled, trigger-eligible supporting connectors. Location manual indexing represents a current foreground observation, while its separate default-OFF user-started foreground service stores only newly observed rounded samples and accepts no date-range command. Local Files is non-temporal, and Notifications are event-driven. The Sources UI therefore exposes only fixed 7-, 30-, and 90-day range actions on the three supporting rows. Inclusive local dates become half-open instant bounds at local midnights, which preserves 23- and 25-hour daylight-saving days; connector queries use the exclusive upper bound. App Usage consumes bounded foreground/background `UsageEvents` inside those exact bounds rather than daily aggregates that Android may expand. Because the platform retains events for only a limited period and does not reveal a session start before the lower bound, every App Usage scan stores and displays a typed partial-history caveat.

The shared executor expands commands into connector tasks, atomically claims only the requested trigger, rechecks connector mode, consent, source enablement, and permission, then scans and commits derived output. A live lease serializes work per connector, while unrelated connectors can still run concurrently; this prevents an older snapshot scan from replacing a newer snapshot for the same connector. Connector readiness and scan calls have a timeout shorter than the 15-minute lease, while connector implementations also cap each scan's item/byte work. The executor and SQLCipher store both require the scan connector ID to match the claimed task. The store checks that identity plus the live item ID, lease owner, attempt number, and lease expiry in the same transaction that writes the derived rows and marks the task complete. Metadata-only completed/skipped/failed transitions use the same live-expiry fence. A reclaimed, canceled, timed-out, or expired worker therefore cannot write stale output or acknowledge an expired lease. Manual expansion excludes event-driven notifications. Automatic expansion includes only background-scannable connectors; the separately user-started Location foreground service is not an automatic worker and serializes its direct writes with Location revoke/delete.

Local Files is ready to attempt a scan whenever at least one document HMAC marker exists; SAF permission is checked per document inside the scan so one revoked grant does not block other selected documents or stale-row reconciliation. It accepts at most 128 new selections, scans markers deterministically, limits one PDF to 64 pages, limits PDF-derived output to 128 pages per connector scan, and applies a 10-minute connector timeout. A legacy selection above 128 is bounded deterministically and produces a typed partial-selection issue instead of an oversized graph. A terminal scan always requests full connector replacement, including zero-output and mixed-success results. A global timeout or cancellation returns no scan result, so the previous snapshot remains intact. User deletion/revocation fences all active tasks for that connector as `SOURCE_DATA_DELETED` before deleting its graph.

Connector scan, timeout, and store exceptions become stable failure codes without exception text. Missing permission, disabled source, ineligible mode, and no indexable output become stable skip reasons. Even a no-output scan commits its encrypted processing state, requested scan scope, and typed missing-source issue codes in the same transaction that skips the queue item. An authoritative empty Calendar, Photos, or App Usage range reports its source-specific `NO_*_IN_RANGE` code. A null platform cursor, service, or event stream instead reports `SOURCE_UNAVAILABLE` and cannot replace prior derived rows. Calendar remains incremental and non-replacing; completed Photos and App Usage scans replace their connector snapshots, including authoritative empty ranges. Calendar and Photos use one-row lookahead before their 200-row derived limits, and App Usage checks both its 100,000 transient-event limit and 100-session derived limit. A reached derived limit keeps the bounded rows but stores a typed partial-range issue; a reached transient App Usage event limit fails closed, stores no fabricated partial sessions, and preserves the prior graph. Status storage never accepts connector prose; the UI converts the fixed code to English, Korean, or Japanese after reading. Duplicate issue tuples and more than 64 issue rows are rejected. An explicit task range overrides connector defaults; otherwise range-based connectors record the default range they actually queried. Ask reuses a scoped missing-source only when the query range is contained by that scan; an unbounded query does not inherit a range-specific result. Snapshot-style connectors can atomically reconcile removed derived rows; event-driven connectors remain incremental. Coroutine cancellation is checked again after potentially blocking connector calls, rethrown, and the running lease is left for bounded recovery. A normal WorkManager stop records a stable stopped outcome instead of an internal error. Sources derives Local Files completion from the encrypted graph and latest scan status; Local Files intentionally has no post-commit preferences checkpoint.

## UI Status

The encrypted queue exposes combined manual/automatic queue depth, only unexpired-lease running connector IDs, last completion time, bounded recent task states, stable skipped/failure reasons, trigger, and indexed-event counts. An expired persisted running lease is presented as recovery pending until the executor requeues it, so it is never shown as live work. A separate singleton runtime row records the latest automatic control or worker activity, outcome, stable reason, and derived-event count without source data. Because a settings change is itself an automatic activity and queue/runtime reads are separate transactions, the UI does not label this singleton as an atomic "last run." Recent task rows preserve actual connector execution outcomes.

Sources loads this status on entry and refreshes only the bounded queue/runtime view every two seconds while the Sources screen and Activity lifecycle are visible. Read failures preserve the last good snapshot and remain retryable on the next interval. The poll does not load connector state, derived events, model status, exception text, or source data. Internal task IDs and lease owners may be materialized by the bounded queue read but are discarded by the UI mapper and never presented. All outcomes and stable reasons are mapped explicitly into English, Korean, and Japanese. One concise dynamic summary is the polite accessibility live region; detailed history remains normally navigable and is not repeatedly announced as a single block.

Each connector row also shows its latest encrypted scan processing state and typed issue codes. When both scope bounds are present, Sources renders the latest requested scan range; the processing state and typed issues separately disclose whether that request completed, produced partial bounded output, or failed closed. Usage Access and Notification Listener connection actions launch only their fixed Android settings destinations, retain only the pending connector ID, and invoke the connector exactly once after Activity result delivery to persist the newly observed access state.

Status text must describe indexing state without logging or retaining source originals.
