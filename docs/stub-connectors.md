# Connector Status

Grayin AI still keeps most MVP connectors as disabled stubs. Local Files has a real Text/Markdown path, and Calendar has a runtime-permission indexing path.

Every connector must report metadata, permission state, scan status, missing-source explanations, and revoke/delete-derived-data contracts.

## Current Connectors

- Location: high sensitivity, default OFF, pending location permission implementation.
- Photos: high sensitivity, default OFF, pending media permission implementation.
- Calendar: high sensitivity, default OFF until user grants calendar permission and invokes it. Reads Android calendar instances transiently and stores only derived calendar events, citations, and source references.
- Notifications: very high sensitivity, default OFF, pending notification-listener implementation.
- App Usage: very high sensitivity, default OFF, pending usage-access implementation.
- Local Files: high sensitivity, default OFF until user selects files. Supports user-selected `.txt` and `.md` documents through Android's document picker.

## Local Files

The Local Files connector:

- stores persisted read permission only after explicit user selection
- reads selected files only inside connector-owned scan scopes
- emits only source references, derived events, and citations
- stores keyword signals and summary metadata, not full file content
- supports revoke and delete-derived-data flows

## Calendar

The Calendar connector:

- requires explicit Android calendar read permission
- requires user invocation before indexing
- reads calendar instances only inside connector-owned scan scopes
- emits source references, derived calendar events, and citations
- supports app-level revoke by disabling the connector and deleting derived data

## Future Connector Work

- Add Android runtime permission handling for location when real location indexing is implemented.
- Add Android media permission handling for photos when real photo indexing is implemented.
- Add notification-listener settings flow when notification indexing is implemented.
- Add usage-access settings flow when app usage indexing is implemented.

Connectors must not read or store source originals outside transient connector processing.

INTERNET permission is allowed for typed weather or reverse-geocode enrichment. Connector and feature code must not call arbitrary URLs or endpoints, upload raw/original source data, or create cloud sync.
