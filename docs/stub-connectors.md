# Connector Status

Grayin AI has real source paths for Local Files, Location, Calendar, Photos, Notifications, and App Usage.

Every connector must report metadata, permission state, scan status, typed missing-source issue codes, and revoke/delete-derived-data contracts. Connector scan status never persists connector-authored explanation prose; localized copy is generated from the code after reading.

## Current Connectors

- Location: high sensitivity, default OFF until user grants location permission and connects it. Reads Android last-known provider location transiently and stores only derived place-visit events, citations, and source references. Optional reverse-geocode/weather enrichment has a separate default-OFF consent.
- Photos: high sensitivity, default OFF until user grants photo permission and connects it. Reads Android MediaStore image metadata transiently and stores only derived photo index events, citations, and source references.
- Calendar: high sensitivity, default OFF until user grants calendar permission and connects it. Reads Android calendar instances transiently and stores only derived calendar events, citations, and source references.
- Notifications: very high sensitivity, default OFF until user enables notification listener access, connects it, and adds exact Android package names to the default-empty allowlist. Reads only allowed posted notifications transiently and stores only derived notification signal events, citations, and source references.
- App Usage: very high sensitivity, default OFF until user grants usage access and connects it. Reads Android usage stats transiently and stores only derived app-usage events, citations, and source references.
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
- requires user connection before indexing
- reads calendar instances only inside connector-owned scan scopes
- emits source references, derived calendar events, and citations
- supports app-level revoke by disabling the connector and deleting derived data

## Location

The Location connector:

- requires explicit Android location permission
- requires user connection before indexing
- reads last-known provider location only inside connector-owned scan scopes
- stores rounded location signals in derived place-visit events
- exposes a separate online-enrichment switch with provider/data-retention disclosure
- when enabled, calls only typed gateway methods; when disabled or unavailable, keeps coordinate-only local indexing
- emits source references, derived place-visit events, and citations
- supports app-level revoke by disabling the connector and enrichment consent and deleting derived data

## Photos

The Photos connector:

- requires explicit Android photo/media read permission
- requires user connection before indexing
- reads MediaStore image metadata only inside connector-owned scan scopes
- never reads or copies original image bytes
- exposes media capability only; metadata orientation/type labels are not treated as visual-content labels
- emits source references, derived photo index events, and citations
- supports app-level revoke by disabling the connector and deleting derived data

## App Usage

The App Usage connector:

- requires explicit Android usage-access settings permission
- requires user connection before indexing
- reads UsageStats only inside connector-owned scan scopes
- stores only derived app-duration summaries and source references
- does not store raw usage event dumps
- supports app-level revoke by disabling the connector and deleting derived data

## Notifications

The Notifications connector:

- requires explicit Android notification-listener settings access
- requires user connection before indexing
- requires a user-managed application-package allowlist that defaults to empty
- rejects malformed package entries and checks the posting package before reading notification extras
- reads posted notification title/text only transiently inside listener callbacks
- skips security-code notifications
- stores only derived notification signal events and source references
- does not store raw notification text
- supports app-level revoke by disabling the connector, clearing the allowlist, and deleting derived data

Connectors must not read or store source originals outside transient connector processing.

INTERNET permission is used for typed map/place, weather, or reverse-geocode enrichment and fixed-catalog model, authenticated manifest, or user-selected OCR language-data downloads. Connectors and indexing code cannot initiate artifact downloads or call arbitrary/user-supplied URLs, upload raw or derived user data, invoke remote LLMs, or create cloud sync. See `docs/network-policy.md`.
