# Encrypted Export and Import

Grayin supports an explicit, password-protected document export/import flow. It is not Android backup, account storage, cloud sync, or an application-backend transfer. The document picker is launched with `EXTRA_LOCAL_ONLY`, which requests an already-on-device document, and the app never persists the selected backup URI permission. Because the picker provider is an external Android component, the user must still choose an on-device location; Grayin has no backup network client and cannot guarantee another provider's storage or later sync behavior.

## Version 1 Data Scope

The encrypted payload contains exactly these seven derived-memory sections, including empty arrays:

1. source references
2. derived memory events (including the entity graph carried by each event)
3. citations
4. reserved daily summaries (the array must be empty in schema v8)
5. place clusters
6. reserved app usage summaries (the array must be empty in schema v8)
7. connector scan statuses

Rows are sorted by stable ID, or connector ID for scan statuses. Export validates the complete graph before encryption. Every exported `SourceReference.localPointer` is set to `null`; a backup never contains a device-local URI, path, persisted grant, or another value that can relink an imported row to its original. Schema v8 retains the `dailySummaries` and `appUsageSummaries` arrays for the fixed version-1 wire shape but has no canonical producer for either, so both arrays must be empty. Allowed per-session App Usage events, other event summaries, labels, citation labels, application identifiers, and one-way source hashes remain part of the other sections.

The format excludes SQLCipher files and passphrases, Android Keystore/HMAC keys, connector preferences and permissions, SAF grants, notification allowlists, online-enrichment consent, automatic-indexing settings/queue/runtime, model or OCR artifacts, logs, originals, prompts, evidence packs, and answers.

## Authenticated Binary Envelope

Version 1 uses a binary envelope rather than ZIP, Base64, or compression. The fixed 64-byte big-endian header is authenticated verbatim as AES-GCM additional authenticated data.

| Offset | Field | Version 1 value |
| --- | --- | --- |
| 0..7 | magic | ASCII `GRAYINEX` |
| 8..9 | envelope version | unsigned 16-bit `1` |
| 10..11 | header length | unsigned 16-bit `64` |
| 12 | KDF ID | `1`, PBKDF2-HMAC-SHA256 |
| 13 | cipher ID | `1`, AES-256-GCM |
| 14..15 | flags | `0` |
| 16..19 | KDF iterations | unsigned 32-bit `600000` |
| 20 | salt length | `16` |
| 21 | nonce length | `12` |
| 22 | tag length | `16` |
| 23 | reserved | `0` |
| 24..27 | plaintext length | unsigned 32-bit, at most 32 MiB |
| 28..31 | ciphertext length | plaintext length plus 16-byte tag |
| 32..47 | random salt | 16 bytes |
| 48..59 | random nonce | 12 bytes |
| 60..63 | reserved | all zero |

The password is used exactly as entered, without trimming or Unicode normalization, and must contain 12 to 128 UTF-16 code units. A random 16-byte salt and 600,000 PBKDF2-HMAC-SHA256 iterations derive a 256-bit key. Encryption uses `AES/GCM/NoPadding`, a random 12-byte nonce, and a 128-bit tag. Password arrays, derived keys, and plaintext buffers are cleared on a best-effort basis after use.

Parsing validates the fixed header, supported IDs, reserved bytes, iteration count, bounded lengths, and exact file length before invoking the KDF or allocating from envelope-controlled lengths. Truncation, trailing data, tampering, and a wrong password fail closed; wrong-password and authentication failures share one user-visible result so the UI does not expose an oracle.

## Strict Payload Validation

The authenticated plaintext is strict UTF-8 JSON with `payloadVersion: 1`, a creation timestamp, producer metadata declaring the current store schema v8, and exactly the seven mandatory arrays. A producer schema other than v8 is rejected as an invalid payload; legacy data must be opened and migrated by a compatible app before export. Unknown or missing fields, unknown enum values, duplicate IDs, unsupported connector IDs, non-finite/range-invalid values, oversized fields or collections, malformed or unsafe Unicode, and malformed timestamps are rejected.

Validation also requires a closed graph:

- every event source and citation ID exists
- every citation references an existing source and event
- event-to-citation links are bidirectional
- both reserved aggregate arrays are empty; a legacy or otherwise arbitrary daily or app-usage summary is rejected even if its references form a valid graph
- place clusters reference existing sources and events where applicable
- connector scan statuses use stable issue codes only
- Local Files rows satisfy the HMAC-only closed schema
- Location, Photos, Calendar, Notifications, and App Usage rows satisfy the same connector-specific schema-v8 closed-record validator used for live scans; App Usage is represented by canonical per-session events, not aggregate rows

An incoming live Location cluster contains one observation. A stored/exported accumulated cluster may contain multiple distinct location source references only when they cover the stored location graph exactly once, its visit count equals that reference count, its first/last times equal the referenced source extrema, its confidence equals the strongest referenced event, its centroid remains on the 0.001-degree grid, and its ID retains the closed 32-hex suffix.

The plaintext limit is 32 MiB, with tighter per-section, row, list, and string bounds. The export path applies the same validator and therefore refuses legacy or malformed stored graphs instead of encrypting them.

## Export Flow

1. The user enters and confirms a password. Password state is never saveable, logged, or persisted.
2. Grayin reads one consistent SQLCipher snapshot, detaches local pointers, validates it, and writes only authenticated ciphertext to a random token under `noBackupFilesDir`.
3. Grayin launches `ACTION_CREATE_DOCUMENT` with `EXTRA_LOCAL_ONLY`, the Grayin backup MIME type, and a `.grayin` file name. The user is told to choose an on-device destination.
4. The staged ciphertext is copied to the chosen document and closed before success is reported.
5. The encrypted staging file is deleted on success or cancellation. Stale encrypted stages are removed on later startup.

No plaintext staging file is created. A partial destination is not accepted as a backup because its length or authentication tag is invalid.

## Import Flow

Import is replace-only; version 1 never merges graphs.

1. The UI warns that import replaces all current derived memory, does not restore originals, permissions, settings, or local document links, disables automatic indexing and online enrichment, and requires every source to be reconnected.
2. An `ACTION_OPEN_DOCUMENT` contract with `EXTRA_LOCAL_ONLY` requests an on-device source and copies the bounded encrypted file into `noBackupFilesDir`. Magic, version, and length are preflighted without taking a persistent URI grant.
3. The password is entered transiently. The complete envelope is authenticated before JSON parsing or any mutation.
4. The payload producer schema, closed Unicode, connector-specific canonical records, and complete graph are decoded and validated in memory. This completes before connector consent, settings, or the existing store can be changed.
5. Grayin durably disables automatic indexing, synchronizes/cancels its WorkManager job, turns online enrichment off, revokes every connector's app-level consent, clears the notification allowlist, and releases all Local Files grants.
6. One SQLCipher transaction fences the automatic-indexing generation, clears the queue/runtime, replaces all seven wire sections—with `dailySummaries` and `appUsageSummaries` required to be empty—using conflict-aborting inserts, and installs a re-consent barrier for every trusted connector.
7. The encrypted stage and password are cleared after success. Password-policy, authentication, consent-reset, and store-transaction failures retain only the encrypted stage for an explicit retry; terminal format, payload, size, and I/O failures discard it.

If authentication, decoding, validation, consent reset, or the SQLCipher transaction fails, the existing derived-memory snapshot is not partially replaced.

## Connector Re-Consent

The SQLCipher re-consent table is authoritative. The indexing executor checks it before connector state, permission, or `scan()` calls, and both direct and queue-fenced scan writes check it again inside their write transaction. The Notification listener serializes consent reset with its direct write path.

Imported historical evidence remains searchable, but refresh/relink is blocked until an explicit successful connector action clears only that connector's barrier. Local Files requires a new successful document selection; its old URI grants and links are never restored. The Sources UI displays `Reconnection required` even when an Android platform permission remains granted.

## Stable Failures

The transfer layer exposes bounded codes only: canceled, source I/O failed, destination I/O failed, password policy failed, invalid format, unsupported version, too large, authentication failed, invalid payload, consent reset failed, store transaction failed, and cryptography unavailable. URI, file name, password, provider response, exception message, and data content are never included in UI state or logs.
