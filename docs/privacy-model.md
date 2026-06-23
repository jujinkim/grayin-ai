# Privacy Model

Grayin AI is designed around user-owned local memory.

## Defaults

- Local-first by default.
- Internet permission is allowed only through typed online enrichment methods.
- No account.
- No server.
- No cloud.
- No telemetry.
- No ads.
- No crash analytics SDK.
- No raw data retention.

## Online Enrichment

Online enrichment may fetch weather or reverse-geocode information.

Allowed network requests must:

- use explicit lookup inputs such as latitude/longitude, timestamp, or coarse place query
- call typed internal methods such as `getWeather` or `reverseGeocode`
- avoid arbitrary URL or endpoint parameters in app feature code
- never upload raw/original source data
- never create cloud sync, account storage, telemetry, ads, or crash analytics
- be explainable in policy docs and UI copy before use

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

Final implementation must encrypt local storage.

## Local Store Security

- SQLCipher-backed persistence stores derived memory data at rest.
- Android Keystore protects the generated SQLCipher passphrase.
- Store APIs must accept only source references, derived memory, citations, summaries, clusters, and index metadata.
- Android backup remains disabled in the MVP manifest until encrypted export/import is implemented.
