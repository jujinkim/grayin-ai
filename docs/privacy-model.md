# Privacy Model

Grayin AI is designed around user-owned local memory.

## Defaults

- Local-first by default.
- Network access is allowed only through typed external enrichment and fixed-catalog artifact download boundaries.
- No account.
- No application backend.
- No cloud storage or sync.
- No telemetry.
- No ads.
- No crash analytics SDK.
- No raw data retention.

## Network Scope

Online enrichment may fetch map/place, weather, or reverse-geocode information. Fixed artifact infrastructure may download a model file, signed manifest, or user-selected OCR language pack from the app catalog. Internal OCR WorkManager input contains only a fixed pack ID and generation. The artifact host sees the selected fixed pack path plus ordinary network metadata such as the device IP address, but the request contains no selected-document identity, URI, content, query, evidence, or memory data.

External enrichment has a separate default-OFF user switch under Location. Reverse geocoding sends a coordinate rounded to 0.001 degrees through Android `Geocoder`. Open-Meteo weather sends a coordinate rounded to 0.01 degrees plus one UTC date. It does not send the stored location event, source reference, query, or user label.

The provider still sees network metadata. Open-Meteo's published terms say API request logs may include IP addresses and URLs and may be retained for 90 days. The UI discloses this before opt-in. Current public Open-Meteo endpoints are non-commercial prototype endpoints and require CC BY 4.0 attribution.

Allowed network requests must:

- use minimal derived lookup inputs such as rounded latitude/longitude, timestamp, locale, units, or an approved coarse place query
- call typed gateway methods for map/place, weather, or reverse-geocode operations
- use fixed HTTPS catalog entries for model, manifest, or OCR language-data downloads
- avoid arbitrary or user-supplied URL and endpoint parameters
- never upload raw/original source data
- never upload stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside the approved ephemeral enrichment-request projection
- never create cloud sync, account storage, application backend state, telemetry, ads, or crash analytics
- be explainable in policy docs and UI copy before use

`docs/network-policy.md` is the canonical network boundary.

## Consent

Every connector requires explicit opt-in.

Encrypted import never restores connector consent. It releases Local Files grants, turns off online enrichment and automatic indexing, and installs an encrypted re-consent barrier for every connector. Imported historical derived evidence remains readable, but no connector may access originals again until that source is explicitly reconnected.

Location source consent and online-enrichment consent are separate. Disabling or revoking Location disables online enrichment. Without enrichment consent, Location continues to index the rounded local coordinate and does not call the gateway.

Each connected Location scan reads only Android's current last-known observation. Grayin derives a stable cluster from the 0.001-degree rounded coordinate and accumulates new source observations in SQLCipher; it neither reads a platform location-history archive nor stores the original exact coordinate. With enrichment consent, reverse geocoding and weather are attempted independently. A failure leaves coordinate-only or place-only local evidence intact. Optional place labels are normalized and bounded, and stored weather signals contain only closed numeric WMO/temperature/precipitation fields rather than provider prose or errors.

On Android 14+, Photos distinguishes full-library access from access to only the items selected in the system permission dialog. Selected-only access is usable but is shown as partial, indexing is limited to rows Android exposes, and Sources keeps a system reselection action available. A successful photo query is the full stored snapshot for the latest requested half-open range, so a later reselection, deletion, or narrower range atomically removes rows Android no longer returns; a failed query retains the prior snapshot. Grayin does not persist a photo file name. Calendar title/location and app labels are normalized, stripped of control/format/private-use/unassigned characters, and UTF-8 bounded before derived storage; malformed Unicode is discarded and coordinate-only Location output remains available. Calendar display/account names are not projected and the calendar source-app marker is fixed. Notification extras are transiently bounded before classification; an oversized arrival is skipped in full.

Every connector must support:

- enable
- disable
- revoke
- delete derived data
- show permission state
- show indexing state

## Derived Data Is Sensitive

Even without raw originals, derived data can reveal sensitive information.

Examples:

- place clusters
- payment events
- app usage patterns
- photo keywords
- OCR text
- calendar summaries
- daily summaries

Local derived-memory storage is encrypted with SQLCipher and an Android Keystore-protected passphrase.

## Local Store Security

- SQLCipher-backed persistence stores derived memory data at rest.
- Android Keystore protects the generated SQLCipher passphrase.
- Store APIs must accept only source references, derived memory, citations, summaries, clusters, and index metadata.
- Android backup remains disabled through the manifest plus explicit legacy and Android 12+ cloud/device-transfer exclusion rules. Encrypted export/import remains an explicit user action and does not enable platform backup.
- Version 1 export contains only the validated seven-section derived snapshot, nulls local pointers, uses password-derived AES-GCM authentication, and stages ciphertext only under `noBackupFilesDir`.

## Optional Screen and App Lock

Screenshot blocking and app lock are separate default-OFF controls. Grayin persists only whether each control is enabled. When app lock is enabled, it also forces the Android secure-window flag so protected content is not intentionally exposed in ordinary screenshots, recordings, Recents previews, or non-secure displays supported by the platform.

App unlock and security-setting changes use Android system authentication. API 30+ uses `BiometricPrompt` with strong-biometric-or-device-credential authentication; API 26–29 uses a weak-biometric prompt plus an explicit system PIN/pattern/password fallback. Android, not Grayin, handles biometric templates, PINs, patterns, passwords, enrollment, and matching. Grayin receives only a bounded success/failure callback and stores no biometric or device-credential data, authentication secret, raw system error, or authentication history. Authentication is local and adds no account, server recovery, analytics, or network use.

An enabled app lock starts locked in every new process and relocks when the app enters an ordinary background transition for reasons other than configuration change. Ordinary biometric prompts are invalidated and canceled on background. On API 26–29, only the explicitly recorded current system device-credential Activity handoff survives the resulting `onStop` while the Grayin window remains secure; only current `RESULT_OK` may finish it. Cancellation or another non-success result returns to the authentication purpose's prior stable state: unlock stays locked, enable stays disabled, and disable leaves the already authenticated session enabled. The handoff is one-way, ignores biometric and duplicate-transition callbacks after it starts, and accepts only its matching Activity result. Home, Recents, unrelated external activities, unavailable authentication, superseded attempts, and stale callbacks cannot expose locked content. Device-security settings are the only enrollment/recovery path; returning from settings never enables the feature automatically, and there is no Grayin-specific PIN or bypass. Clearing app data remains the platform last resort and deletes the app's local derived memory and preferences.
