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
- Encrypted local DB in final implementation
- Android Keystore integration
- Backup exclusion through `android:allowBackup="false"` in MVP
- Network permission restricted to typed online enrichment methods
- No arbitrary URL or endpoint calls from app feature code
- No raw/original source upload
- No cloud sync, telemetry, ads, or crash SDK
- No telemetry
- No ads
- No crash SDK
- No raw logs
- Optional screenshot blocking
- Optional biometric app lock
- Connector-level deletion
