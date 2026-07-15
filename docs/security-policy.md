# Security and Backup Policy

Grayin AI uses SQLCipher persistence with an Android Keystore-protected passphrase and an explicit password-protected local export/import path.

## SQLCipher

- Use SQLCipher for the local database.
- Encrypt all source references, derived memory events, citations, summaries, place clusters, app usage summaries, indexes, and entity graph data.
- Keep database pages encrypted at rest.
- Do not add a plaintext fallback database.

## Android Keystore

- Use Android Keystore to protect the generated SQLCipher passphrase.
- Generate or unwrap database keys locally on device.
- Do not sync, export, log, or transmit database keys.
- Rotate keys only through an explicit local migration path.
- Serialize first-time passphrase creation and persist the encrypted passphrase synchronously before returning it to a database opener.
- Keep the Local Files source-identity HMAC-SHA256 key non-exportable in Android Keystore. Domain-separate and length-prefix document and PDF-page inputs and persist the full 64-character lowercase result only.

## Export and Import

- Export uses PBKDF2-HMAC-SHA256 and AES-256-GCM with a versioned, authenticated header.
- Export includes exactly the seven validated derived-memory snapshot sections and detaches every local source pointer.
- Export excludes originals, database/key material, consent/settings, queue/runtime state, artifacts, prompts, answers, and evidence packs.
- Import authenticates and validates the complete payload before mutation, then performs a replace-only SQLCipher transaction.
- Import durably disables automatic indexing and online enrichment, revokes connector app-level consent, releases Local Files grants, and installs an authoritative per-connector re-consent barrier.
- Local-only Android document contracts and ciphertext-only `noBackupFilesDir` staging do not add cloud sync, network transfer, account storage, or server backup.

The exact envelope, limits, validation graph, failure codes, and UI sequence are defined in `docs/export-import.md`.

## Network Permission

The app uses INTERNET permission only for typed map/place/reverse-geocode/weather enrichment and fixed-catalog model, authenticated manifest, or OCR language-data downloads.

External enrichment is separately consented and default OFF. Current traffic is HTTPS-only: Android `Geocoder` receives a 0.001-degree coordinate; fixed Open-Meteo forecast/archive hosts receive a 0.01-degree coordinate and UTC date. Redirects and cleartext traffic are disabled, provider responses are size/schema/range checked, and failures return stable reason enums without response bodies.

Fixed artifact downloads require an immutable HTTPS catalog URL, safe identity, exact byte count, and pinned SHA-256 before transport opens. Redirects, unexpected response type or encoding, short/long bodies, and checksum mismatch fail closed. A verified same-filesystem staging file is flushed and installed with an atomic move, so an interrupted replacement does not delete the last verified file. OCR additionally uses a durable generation to reject stale workers; packs are installed only after a Settings action and remain in `noBackupFilesDir`. Current model entries have incomplete release metadata and cannot start a network download; model generation fencing is required before enabling one.

Network use must not:

- expose arbitrary URL or endpoint calls to app feature code
- upload raw/original source data
- upload stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside an approved ephemeral enrichment-request projection
- create cloud sync or server backup
- send evidence packs to remote LLMs
- add account, telemetry, ads, or crash analytics SDKs

Provider endpoints and artifact URLs must be fixed by trusted provider or catalog configuration. Users, connectors, feature code, and model output cannot supply endpoints. See `docs/network-policy.md`.

## Backup

The manifest sets `android:allowBackup="false"` and references explicit legacy full-backup and Android 12+ data-extraction rules. Both rule sets exclude every credential-encrypted and device-encrypted app domain from cloud backup and device-to-device transfer.

This keeps SQLCipher, preferences, HMAC selection markers, installed artifacts, and migration residue out of Android backup. Export/import remains a separate user-initiated encrypted envelope and does not enable platform backup.

## SDK Exclusions

MVP must not include:

- crash analytics SDKs
- ad SDKs
- telemetry SDKs
- account SDKs
- application-backend, cloud-storage, or cloud-sync SDKs

## Logging

MVP must not log source originals, derived memory contents, source references, evidence packs, prompts, answers, or connector outputs.

## Local Document Runtime

PDFium and Tesseract run only in the private, non-exported `:document` process. The process has the application UID so it can read a duplicated user-granted descriptor and verified app-private OCR packs, but it exposes no intent filter and checks the Binder caller UID. It creates no PDF/image/text temporary files and makes no network calls. The AIDL contract excludes URI, path, name, MIME, source identity, raw text, bitmap, bytes, and exceptions; both processes validate the closed wire-code and payload limits. Native timeout escalation terminates only `:document`, leaving the main process and previous SQLCipher snapshot intact.

Local Files preferences never store a selected URI or file name. They store only a Keystore HMAC marker and match it against Android's persisted SAF grants at scan time. Selection commits the marker before taking its grant, is serialized with revoke, and never takes a new grant after the 128-document bound. Local Files is the sole owner of persisted read grants in this app; revoke releases and then re-enumerates every app-held persisted read grant before it clears the markers. A release or verification failure leaves markers and derived data intact and reports failure. Future SAF features must add an explicit ownership registry before taking a persisted grant.

SQLCipher accepts Local Files output only when every source is HMAC-only, every event uses the closed structural summary/keyword/label/timestamp schema, unrelated graph sections are empty, every citation uses a closed generic/page label, and the scan atomically replaces the connector snapshot. Keystore HMAC failures abort a scan or legacy migration instead of being interpreted as revoked access, preserving the prior snapshot and migration input. Schema v6 removes legacy Local Files identity rows before reindexing. Delete/revoke also clears active queue leases before graph removal so stale workers cannot republish.

## Remaining Hardening

- Add optional screenshot blocking for sensitive screens.
- Add optional biometric app lock for opening the app or viewing sensitive indexed memory.
