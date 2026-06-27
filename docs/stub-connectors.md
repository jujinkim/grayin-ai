# Connector Status

Grayin AI still keeps some MVP connectors as disabled stubs. Local Files has a real Text/Markdown path. Location, Calendar, and Photos have runtime-permission indexing paths.

Every connector must report metadata, permission state, scan status, missing-source explanations, and revoke/delete-derived-data contracts.

## Current Connectors

- Location: high sensitivity, default OFF until user grants location permission and invokes it. Reads Android last-known provider location transiently and stores only derived place-visit events, citations, and source references.
- Photos: high sensitivity, default OFF until user grants photo permission and invokes it. Reads Android MediaStore image metadata transiently and stores only derived photo index events, citations, and source references.
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

## Location

The Location connector:

- requires explicit Android location permission
- requires user invocation before indexing
- reads last-known provider location only inside connector-owned scan scopes
- stores rounded location signals in derived place-visit events
- emits source references, derived place-visit events, and citations
- supports app-level revoke by disabling the connector and deleting derived data

## Photos

The Photos connector:

- requires explicit Android photo/media read permission
- requires user invocation before indexing
- reads MediaStore image metadata only inside connector-owned scan scopes
- never reads or copies original image bytes
- emits source references, derived photo index events, and citations
- supports app-level revoke by disabling the connector and deleting derived data

## Future Connector Work

- Add notification-listener settings flow when notification indexing is implemented.
- Add usage-access settings flow when app usage indexing is implemented.

Connectors must not read or store source originals outside transient connector processing.

INTERNET permission is allowed for typed weather or reverse-geocode enrichment. Connector and feature code must not call arbitrary URLs or endpoints, upload raw/original source data, or create cloud sync.
