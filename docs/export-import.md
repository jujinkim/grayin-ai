# Export and Import Design

MVP 12 documents encrypted export/import design and adds a policy skeleton. It does not implement export, import, file writing, or backup sync.

## Encrypted Export Format

Export files must be encrypted envelopes with:

- format version
- encryption scheme
- key protection description
- creation timestamp
- allowed data sections
- forbidden data list
- encrypted payload in the future implementation

The MVP skeleton models the envelope policy as `EncryptedExportFormat`.

## Allowed Export Data

Encrypted export may include:

- source references
- derived memory events
- citations
- daily summaries
- place clusters
- app usage summaries
- index metadata
- connector metadata

## Forbidden Export Data

Encrypted export must not include:

- photo originals
- PDF originals
- notification originals
- message originals
- usage log originals
- calendar record originals
- local file originals
- audio/video originals

## Import Re-Consent

Import on a new device must require re-consent for every connector before the connector can refresh, relink, or index sources.

Imported derived data remains sensitive and must stay encrypted at rest.

## No Sync

Export/import uses explicit Android document create/open flows only. It does not add automatic network transfer, cloud sync, account storage, application backend state, or server backup. Network scope remains defined by `docs/network-policy.md`.
