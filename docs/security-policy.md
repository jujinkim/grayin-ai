# Security and Backup Policy

MVP 11 documents required security posture. It does not add persistent storage or export implementation.

## SQLCipher Plan

- Use SQLCipher before introducing a real local database.
- Encrypt all source references, derived memory events, citations, summaries, place clusters, app usage summaries, indexes, and entity graph data.
- Keep database pages encrypted at rest.
- Do not add a plaintext fallback database.

## Android Keystore Plan

- Use Android Keystore to protect database encryption keys.
- Generate or unwrap database keys locally on device.
- Do not sync, export, log, or transmit database keys.
- Rotate keys only through an explicit local migration path.

## Export and Import Plan

- Export must be encrypted.
- Export must include only allowed derived data and source references.
- Export must exclude source originals.
- Import on a new device must require connector re-consent before any connector can refresh or re-link sources.

## Backup

The MVP manifest sets `android:allowBackup="false"`.

This excludes the app from normal Android backup until a fully documented encrypted backup/export path exists.

## SDK Exclusions

MVP must not include:

- crash analytics SDKs
- ad SDKs
- telemetry SDKs
- account SDKs
- server/cloud SDKs

## Logging

MVP must not log source originals, derived memory contents, source references, evidence packs, prompts, answers, or connector outputs.

## Optional Hardening TODOs

- TODO: add optional screenshot blocking for sensitive screens.
- TODO: add optional biometric app lock for opening the app or viewing sensitive indexed memory.
