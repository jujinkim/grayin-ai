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

Immutable fixed artifact downloads require an HTTPS catalog URL, safe identity, exact byte count, and pinned SHA-256 before transport opens. Redirects, unexpected response type or encoding, short/long bodies, and checksum mismatch fail closed. A verified same-filesystem staging file is flushed and installed with an atomic move, so an interrupted replacement does not delete the last verified file. A mutable remote model manifest has a different trust boundary: canonical bounded JSON, an ECDSA P-256 signature over the exact payload, app/runtime compatibility and expiry checks, and durable sequence rollback/equivocation rejection. It remains disabled without a reviewed production public key and fixed endpoint. OCR additionally uses a durable generation to reject stale workers; packs are installed only after a Settings action and remain in `noBackupFilesDir`. Current model entries have incomplete release metadata and cannot start a network download.

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

## Optional App Security

- Screenshot blocking and app lock are independent, default-OFF settings persisted synchronously in app-private preferences. A failed preference commit preserves the previous effective policy and reports a stable localized failure.
- The Activity applies `FLAG_SECURE` before sensitive UI is composed and whenever either preference changes. The persistent rule is `screenshot blocking OR app lock`; an enabled app lock therefore keeps `FLAG_SECURE` active even when the separate screenshot toggle is off. A current explicit API 26–29 device-credential handoff is an additional transient secure-window condition so Activity recreation cannot expose the enabling screen before app-lock persistence succeeds.
- `FLAG_SECURE` blocks ordinary screenshots, screen recording, and non-secure-display presentation supported by Android. It is defense in depth, not protection against a rooted device, another camera, or every vendor implementation on older Android releases.
- App lock is a foreground UI gate. It does not replace SQLCipher/Keystore protection, wrap database keys, stop background indexing, or change connector consent.
- Authentication uses Android system UI and permits device credentials. API 30 and newer request `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` through AndroidX `BiometricPrompt`. API 26–29 use a `BIOMETRIC_WEAK` prompt with an explicit system PIN/pattern/password fallback because the combined strong-biometric/credential request is not supported there.
- Grayin stores only the two security preference booleans and process-local state/attempt IDs. Biometric templates, PINs, patterns, passwords, platform error text, and authentication secrets are handled by Android and are never received, stored, logged, exported, or transmitted by Grayin.
- A new process starts locked whenever app lock is enabled. Every ordinary non-configuration background transition relocks immediately and invalidates/cancels an ordinary biometric prompt; configuration changes retain the current process session without launching a duplicate prompt.
- On API 26–29, Grayin explicitly starts the system device-credential Activity only after recording the current attempt ID. Only this exact handoff survives the client Activity's `onStop`, and `FLAG_SECURE` remains forced during it. `RESULT_OK` for the current fenced attempt may complete it; cancellation or another non-success result ends the handoff and returns to the purpose-specific prior stable state, so an unlock remains locked, an enable remains disabled, and a disable leaves the authenticated session enabled. Home, Recents, another external Activity, a superseded attempt, and stale callbacks do not receive this exception.
- The biometric-to-credential transition is one-way for one attempt. Once the handoff marker is set, duplicate transitions and biometric callbacks are ignored; only the matching Activity result may finish it. On API 26–29, a negative-button request, biometric lockout, missing sensor/enrollment, or biometric-only terminal sensor/processing/timeout/security-update error transfers to configured device credentials. Explicit user cancellation and app/background cancellation never trigger a fallback.
- Every prompt callback is fenced by a monotonic process-local attempt ID. A background terminal error invalidates the attempt before content is shown again, and an old callback cannot unlock the app or persist a setting.
- Cancel, failure, lockout, unavailable hardware, missing enrollment, required security update, and persistence failure never expose protected content. The lock screen provides explicit retry and device-security-settings recovery actions; there is no app PIN, recovery question, server recovery, or other authentication bypass.
- Enabling app lock requires configured system authentication and successful authentication before durable persistence. Returning from enrollment/security settings only refreshes capability; it never enables app lock automatically. If system authentication later becomes unavailable, an already enabled lock stays fail-closed until the user restores device security or clears app data, which also deletes local Grayin data.
