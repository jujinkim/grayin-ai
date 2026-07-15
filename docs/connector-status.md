# Connector Status

Grayin AI has real source paths for Local Files, Location, Calendar, Photos, Notifications, and App Usage.

Every connector must report metadata, permission state, scan status, typed missing-source issue codes, and revoke/delete-derived-data contracts. Connector scan status never persists connector-authored explanation prose; localized copy is generated from the code after reading.

Connector state separates persisted app consent from effective platform availability. If Android access is later revoked, Sources shows the localized permission state, disables indexing, keeps reconnect available, and still exposes app-level revoke so stored consent and connector-owned settings can be cleared.

## Current Connectors

- Location: high sensitivity, default OFF until user grants location permission and connects it. Reads Android last-known provider location transiently and stores only derived place-visit events, place clusters, citations, and source references. Optional reverse-geocode/weather enrichment has a separate default-OFF consent.
- Photos: high sensitivity, default OFF until user grants photo permission and connects it. Reads Android MediaStore image metadata transiently and stores only derived photo index events, citations, and source references.
- Calendar: high sensitivity, default OFF until user grants calendar permission and connects it. Reads Android calendar instances transiently and stores only derived calendar events, citations, and source references.
- Notifications: very high sensitivity, default OFF until user enables notification listener access, connects it, and adds exact Android package names to the default-empty allowlist. Reads only allowed posted notifications transiently and stores only derived notification signal events, citations, and source references.
- App Usage: very high sensitivity, default OFF until user grants usage access and connects it. Reads Android usage stats transiently and stores only derived app-usage events, citations, and source references.
- Local Files: high sensitivity, default OFF until user selects documents. Supports Text, Markdown, and PDF through Android's document picker.

## Local Files

The Local Files connector:

- asks Android to persist read permission only after explicit user selection
- stores only a Keystore HMAC marker and resolves the live URI from Android's SAF grant list during a scan
- reads selected files only inside connector-owned scan scopes
- processes PDFs in the private on-device Pdfium/Tesseract service and accepts only its validated bounded result
- emits only HMAC source references, derived events, and closed citations without a URI or file name
- stores keyword signals and summary metadata, not full file content
- replaces the full Local Files snapshot atomically and preserves the previous snapshot if the 10-minute connector timeout cancels the scan
- supports revoke and delete-derived-data flows

## Calendar

The Calendar connector:

- requires explicit Android calendar read permission
- requires user connection before indexing
- reads calendar instances only inside connector-owned scan scopes
- emits source references, derived calendar events, and citations
- supports app-level revoke by disabling the connector and deleting derived data
- supports bounded manual date ranges
- reads one row past its 200-event output bound to report a typed partial-range status instead of claiming full coverage
- normalizes and independently UTF-8-bounds title and location before derived storage or citation; does not project calendar display/account names and uses fixed `android-calendar` source-app metadata

## Location

The Location connector:

- requires explicit Android location permission
- requires user connection before indexing
- reads last-known provider location only inside connector-owned scan scopes
- reduces the Android provider name to `gps`, `network`, `passive`, `fused`, or `other` before identity, pointer, keyword, or label persistence
- stores rounded location signals in derived place-visit events
- emits a stable place cluster for the 0.001-degree rounded coordinate and merges later observations idempotently inside the SQLCipher scan transaction
- accumulates only observations seen by user-triggered scans; it does not read an Android location-history archive
- exposes a separate online-enrichment switch with provider/data-retention disclosure
- when enabled, calls only typed gateway methods; when disabled or unavailable, keeps coordinate-only local indexing
- emits source references, derived place-visit events, place clusters, and citations
- supports app-level revoke by disabling the connector and enrichment consent and deleting derived data

## Photos

The Photos connector:

- requires explicit Android photo/media read permission
- distinguishes Android 14+ full-library access from selected-photos-only access and keeps system reselection available for the partial state
- requires user connection before indexing
- reads MediaStore image metadata only inside connector-owned scan scopes
- never reads or copies original image bytes
- never stores MediaStore `DISPLAY_NAME` or another photo file name; MIME is reduced to a closed image allowlist and dimensions to plausible positive values
- exposes media capability only; metadata orientation/type labels are not treated as visual-content labels
- emits source references, derived photo index events, and citations
- supports app-level revoke by disabling the connector and deleting derived data
- supports bounded manual date ranges with an exclusive upper instant bound
- uses `DATE_MODIFIED` seconds for selection and ordering when `DATE_TAKEN` is missing or non-positive
- reads one row past its 200-photo output bound to report a typed partial-range status instead of claiming full coverage

## App Usage

The App Usage connector:

- requires explicit Android usage-access settings permission
- requires user connection before indexing
- reads Android `UsageEvents` transitions only inside the requested half-open connector scan scope
- stores only derived app-duration summaries and source references
- does not store raw usage event dumps
- supports app-level revoke by disabling the connector and deleting derived data
- supports bounded manual date ranges without persisting provider-expanded daily buckets
- emits only completed, non-overlapping foreground sessions with stable identities; an in-progress or lower-bound-crossing session is omitted rather than guessed
- reports typed limited-history, transient-event-limit, and derived-output-limit issues; transient input truncation stores no partial result
- atomically replaces the App Usage connector snapshot for a completed bounded scan, which removes legacy expanded aggregates and prevents overlapping range totals; a transient input-limit failure preserves the prior snapshot
- skips package names outside the closed 255-byte grammar, bounds app aliases to one 256-byte line, and caps activity-class input before transient aggregation

## Notifications

The Notifications connector:

- requires explicit Android notification-listener settings access
- requires user connection before indexing
- requires a user-managed application-package allowlist that defaults to empty
- rejects malformed package entries and checks the posting package before reading notification extras
- reads posted notification title/text only transiently inside listener callbacks
- caps normalized classifier input at 4 KiB and skips an entire oversized arrival rather than classifying a truncated prefix
- maps app-authored notification category values through a closed Android category set before classification or derived labels
- skips security-code notifications
- stores only derived notification signal events and source references
- does not store raw notification text
- supports app-level revoke by disabling the connector, clearing the allowlist, and deleting derived data

Connectors must not read or store source originals outside transient connector processing.

INTERNET permission is used for typed map/place, weather, or reverse-geocode enrichment and fixed-catalog model, authenticated manifest, or user-selected OCR language-data downloads. Connectors and indexing code cannot initiate artifact downloads or call arbitrary/user-supplied URLs, upload raw or derived user data, invoke remote LLMs, or create cloud sync. See `docs/network-policy.md`.
