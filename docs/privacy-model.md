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

Online enrichment may fetch map/place, weather, or reverse-geocode information. Runtime model infrastructure may download a model file or signed manifest selected from the fixed app catalog.

Allowed network requests must:

- use minimal derived lookup inputs such as rounded latitude/longitude, timestamp, locale, units, or an approved coarse place query
- call typed gateway methods for map/place, weather, or reverse-geocode operations
- use fixed HTTPS catalog entries for model or manifest downloads
- avoid arbitrary or user-supplied URL and endpoint parameters
- never upload raw/original source data
- never upload stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside the approved ephemeral enrichment-request projection
- never create cloud sync, account storage, application backend state, telemetry, ads, or crash analytics
- be explainable in policy docs and UI copy before use

`docs/network-policy.md` is the canonical network boundary.

## Consent

Every connector requires explicit opt-in.

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
- Android backup remains disabled in the MVP manifest until encrypted export/import is implemented.
