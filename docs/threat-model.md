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
- Lock-screen bypass through a canceled, stale, or post-background authentication callback
- Silent weakening after a security-preference persistence failure or removed device credential
- Connector over-collection
- Accidental raw data persistence
- Online enrichment metadata leakage
- Artifact supply-chain substitution or redirect
- Interrupted artifact replacement
- Stale download worker publication
- Malformed or adversarial PDF native-parser failure
- Stuck or memory-exhausting local OCR work
- Persisted document URI or file-name identity leakage
- In-flight connector publication after user deletion or revocation
- Malformed, oversized, downgraded, tampered, or wrong-password export files
- Connector access continuing after imported derived memory is restored

## Mitigations

- No raw data retention
- SQLCipher-encrypted local DB with Android Keystore passphrase protection
- Android Keystore integration
- Backup exclusion through `android:allowBackup="false"`, legacy full-backup exclusions, and Android 12+ cloud/device-transfer extraction exclusions
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
- Persisted optional screenshot blocking through `FLAG_SECURE`
- Persisted optional system biometric/device-credential app lock
- Effective secure-window policy whenever either screenshot blocking or app lock is enabled, plus every current explicit device-credential handoff
- API 30+ strong-biometric-or-device-credential policy and API 26–29 weak-biometric prompt with explicit system device-credential fallback
- Fail-closed process start and non-configuration background relock, with configuration-change continuity and ordinary prompt cancellation
- Monotonic attempt-ID fencing that rejects canceled, superseded, background-error, and stale authentication callbacks while allowing only the explicitly recorded current system credential handoff
- Synchronous preference persistence with rollback to the previous effective policy on failure
- System security-settings recovery for missing enrollment or unavailable credentials, without an app-owned PIN or bypass
- No biometric template, device credential, authentication secret, or raw platform error storage or logging
- Connector-level deletion
- Private `:document` process for PDFium/Tesseract crash and memory-pressure containment
- Seekability/signature/size/page/render/text/OCR/time checks before derived output is accepted
- Derived-only bounded Binder result with death handling and no partial store commit
- HMAC-only Local Files selection/source identity with closed citation labels and schema-v6 legacy purge
- Atomic queue-lease fencing before connector deletion or revocation
- PBKDF2-HMAC-SHA256 plus AES-256-GCM export envelope with authenticated fixed header, random salt/nonce, strict lengths, and bounded pre-KDF parsing
- Strict seven-section payload schema, closed-graph validation, detached local pointers, and a replace-only SQLCipher import transaction
- Ciphertext-only no-backup staging and local-only Android document contracts without persisted backup URI grants
- Per-connector SQLCipher re-consent barriers checked before source reads and again in scan-write transactions

`FLAG_SECURE` and the foreground app lock reduce casual local disclosure but do not defend against root compromise, physical observation, or every older vendor screenshot implementation. SQLCipher/Keystore storage protection, connector consent, and zero-raw-retention remain independent controls.

## Network Metadata Risk

External enrichment can reveal a rounded coordinate/date and the device IP address to the selected provider. Current reverse-geocode projection uses 0.001-degree coordinates through Android `Geocoder`; weather uses 0.01-degree coordinates and one UTC date through fixed Open-Meteo forecast/archive endpoints. Open-Meteo states that URL/IP request logs may be retained for 90 days. The mitigation is a separate default-OFF consent, coarse projections, fixed HTTPS hosts/queries, disabled redirects and cleartext traffic, response caps/schema checks, no retries through other providers, and coordinate-only local fallback.

Model and OCR language-data downloads reveal the selected fixed catalog item and network metadata to its artifact host. They must not include document identity, user-memory identifiers, or evidence data. See `docs/network-policy.md`.

Artifact supply-chain and interrupted-install risks are reduced with an APK-authenticated closed catalog, immutable HTTPS paths, exact byte counts, pinned SHA-256 digests, redirect/header rejection, flushed same-filesystem staging, and atomic replacement. OCR download state additionally uses generation fencing, so failed, canceled, or stale OCR replacement preserves any last verified pack. Only Settings can enqueue an OCR language pack; connectors and indexing code cannot do so. Current model transport entries remain disabled until complete reviewed release metadata and equivalent stale-worker fencing exist.
