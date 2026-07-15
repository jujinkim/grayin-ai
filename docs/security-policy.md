# Security and Backup Policy

Grayin AI uses SQLCipher persistence with an Android Keystore-protected passphrase. Encrypted export/import remains under implementation.

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

## Export and Import Plan

- Export must be encrypted.
- Export must include only allowed derived data and source references.
- Export must exclude source originals.
- Import on a new device must require connector re-consent before any connector can refresh or re-link sources.
- MVP export/import design must not add cloud sync, network transfer, account storage, or server backup.

## Network Permission

The app uses INTERNET permission only for typed map/place/reverse-geocode/weather enrichment and fixed-catalog model/manifest downloads.

Network use must not:

- expose arbitrary URL or endpoint calls to app feature code
- upload raw/original source data
- upload stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside an approved ephemeral enrichment-request projection
- create cloud sync or server backup
- send evidence packs to remote LLMs
- add account, telemetry, ads, or crash analytics SDKs

Provider endpoints and artifact URLs must be fixed by trusted provider or catalog configuration. Users, connectors, feature code, and model output cannot supply endpoints. See `docs/network-policy.md`.

## Backup

The MVP manifest sets `android:allowBackup="false"`.

This excludes the app from normal Android backup until a fully documented encrypted backup/export path exists.

## SDK Exclusions

MVP must not include:

- crash analytics SDKs
- ad SDKs
- telemetry SDKs
- account SDKs
- application-backend, cloud-storage, or cloud-sync SDKs

## Logging

MVP must not log source originals, derived memory contents, source references, evidence packs, prompts, answers, or connector outputs.

## Remaining Hardening

- Add optional screenshot blocking for sensitive screens.
- Add optional biometric app lock for opening the app or viewing sensitive indexed memory.
