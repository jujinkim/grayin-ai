# Threat Model

## Assets to Protect

- Source references
- Derived memory events
- Photo indexes
- Notification-derived events
- Place clusters
- App usage summaries
- Calendar summaries
- OCR-derived text
- Embeddings
- Entity graph
- Daily/weekly summaries
- Export files

## Threats

- Device loss
- Malicious apps
- Rooted devices
- Debug logs
- Crash dumps
- OS backup leakage
- Export file leakage
- Screenshots/screen recordings
- Connector over-collection
- Accidental raw data persistence
- Online enrichment metadata leakage
- Artifact supply-chain substitution or redirect
- Interrupted artifact replacement
- Stale download worker publication

## Mitigations

- No raw data retention
- SQLCipher-encrypted local DB with Android Keystore passphrase protection
- Android Keystore integration
- Backup exclusion through `android:allowBackup="false"` in MVP
- Network permission restricted to typed external enrichment and fixed-catalog artifact downloads
- Provider URLs and schemas fixed by trusted gateway/catalog configuration
- No arbitrary or user-supplied URL or endpoint calls
- No raw/original source upload
- No stored derived-memory record, evidence pack, prompt, answer, embedding, source-reference, or non-approved enrichment field upload
- No cloud sync, telemetry, ads, or crash SDK
- No telemetry
- No ads
- No crash SDK
- No raw logs
- Optional screenshot blocking
- Optional biometric app lock
- Connector-level deletion

## Network Metadata Risk

External enrichment can reveal a rounded coordinate/date and the device IP address to the selected provider. Current reverse-geocode projection uses 0.001-degree coordinates through Android `Geocoder`; weather uses 0.01-degree coordinates and one UTC date through fixed Open-Meteo forecast/archive endpoints. Open-Meteo states that URL/IP request logs may be retained for 90 days. The mitigation is a separate default-OFF consent, coarse projections, fixed HTTPS hosts/queries, disabled redirects and cleartext traffic, response caps/schema checks, no retries through other providers, and coordinate-only local fallback.

Model and OCR language-data downloads reveal the selected fixed catalog item and network metadata to its artifact host. They must not include document identity, user-memory identifiers, or evidence data. See `docs/network-policy.md`.

Artifact supply-chain and interrupted-install risks are reduced with an APK-authenticated closed catalog, immutable HTTPS paths, exact byte counts, pinned SHA-256 digests, redirect/header rejection, flushed same-filesystem staging, and atomic replacement. OCR download state additionally uses generation fencing, so failed, canceled, or stale OCR replacement preserves any last verified pack. Only Settings can enqueue an OCR language pack; connectors and indexing code cannot do so. Current model transport entries remain disabled until complete reviewed release metadata and equivalent stale-worker fencing exist.
