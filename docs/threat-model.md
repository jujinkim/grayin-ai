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

External enrichment can reveal a rounded coordinate, timestamp, locale, units, or coarse place query to the selected provider. Only fields required by a typed operation may leave the device. Provider failures must degrade to local results without retrying through unapproved endpoints.

Model downloads reveal model selection and network metadata to the fixed artifact host. They must not include user-memory identifiers or evidence data. See `docs/network-policy.md`.
