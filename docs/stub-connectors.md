# Stub Connectors

MVP 6 adds disabled connector stubs only. They do not read device sources.

Each stub reports metadata, permission state, skipped scan status, missing-source explanations, and revoke/delete-derived-data contracts.

## Stubs

- Location: high sensitivity, default OFF, pending location permission implementation.
- Photos: high sensitivity, default OFF, pending media permission implementation.
- Calendar: high sensitivity, default OFF, pending calendar permission implementation.
- Notifications: very high sensitivity, default OFF, pending notification-listener implementation.
- App Usage: very high sensitivity, default OFF, pending usage-access implementation.
- Local Files: high sensitivity, default OFF, pending user-selected file/folder access implementation.

## Platform Permission TODOs

- TODO: add Android runtime permission handling for location when real location indexing is implemented.
- TODO: add Android media permission handling for photos when real photo indexing is implemented.
- TODO: add Android calendar permission handling when real calendar indexing is implemented.
- TODO: add notification-listener settings flow when notification indexing is implemented.
- TODO: add usage-access settings flow when app usage indexing is implemented.
- TODO: add system picker flow for user-selected local files and folders when local file indexing is implemented.

No stub adds manifest permissions, no stub requests network access, and no stub reads or stores source originals.
