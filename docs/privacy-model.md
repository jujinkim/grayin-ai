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

Online enrichment may fetch map/place, weather, or reverse-geocode information. Fixed artifact infrastructure may download a model file, signed manifest, or user-selected OCR language pack from the app catalog. Internal OCR WorkManager input contains only a fixed pack ID and generation. The artifact host sees the selected fixed pack path plus ordinary network metadata such as the device IP address, but the request contains no selected-document identity, URI, content, query, evidence, or memory data.

External enrichment has a separate default-OFF user switch under Location. Reverse geocoding sends a coordinate rounded to 0.001 degrees through Android `Geocoder`. Open-Meteo weather sends a coordinate rounded to 0.01 degrees plus one UTC date. It does not send the stored location event, source reference, query, or user label.

The provider still sees network metadata. Open-Meteo's published terms say API request logs may include IP addresses and URLs and may be retained for 90 days. The UI discloses this before opt-in. Current public Open-Meteo endpoints are non-commercial prototype endpoints and require CC BY 4.0 attribution.

Allowed network requests must:

- use minimal derived lookup inputs such as rounded latitude/longitude, timestamp, locale, units, or an approved coarse place query
- call typed gateway methods for map/place, weather, or reverse-geocode operations
- use fixed HTTPS catalog entries for model, manifest, or OCR language-data downloads
- avoid arbitrary or user-supplied URL and endpoint parameters
- never upload raw/original source data
- never upload stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or fields outside the approved ephemeral enrichment-request projection
- never create cloud sync, account storage, application backend state, telemetry, ads, or crash analytics
- be explainable in policy docs and UI copy before use

`docs/network-policy.md` is the canonical network boundary.

## Consent

Every connector requires explicit opt-in.

Location source consent and online-enrichment consent are separate. Disabling or revoking Location disables online enrichment. Without enrichment consent, Location continues to index the rounded local coordinate and does not call the gateway.

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
- Android backup remains disabled through the manifest plus explicit legacy and Android 12+ cloud/device-transfer exclusion rules. Encrypted export/import remains an explicit user action and does not enable platform backup.
