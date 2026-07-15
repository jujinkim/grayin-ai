# Zero Raw Retention

Zero Raw Retention is the central privacy rule of Grayin AI.

## Definition

Raw original data is only accessed transiently in memory, processed immediately, and discarded.

It must never be:

- stored
- logged
- cached
- exported
- synced
- transmitted
- backed up
- passed to the local store
- passed to the LLM

Network-enabled enrichment does not weaken this rule. Only an ephemeral `EnrichmentRequest` projection approved by `docs/network-policy.md` may be transmitted to a map/place, reverse-geocode, or weather provider. It may contain required rounded coordinates, timestamp, locale, units, or an approved coarse query, but never a persisted `DerivedMemoryEvent`. Raw originals, stored derived-memory records, evidence packs, prompts, answers, embeddings, and source references never leave the device.

## Raw Original Examples

- original photos
- notification text
- message text
- PDF files
- local file content
- calendar raw records
- app usage event dumps
- browser history dumps
- note files
- audio/video originals

## Allowed Data

Grayin AI may store:

- SourceReference
- HMAC hash
- timestamp
- source app/package
- minimal extracted fields
- summary
- keywords
- labels
- entities
- embedding
- citation metadata
- confidence
- derived memory events
- daily summaries

All derived data is still sensitive and remains encrypted in the SQLCipher store.

## Engineering Rule

There must be no store API that accepts raw content.

Connectors may read raw source data only inside connector-owned transient processing scopes. They may emit only derived events and source references.

## Core Model Rule

Core model types may represent only:

- source references
- derived memory events
- derived summaries
- keywords, labels, and entities
- citation metadata
- confidence
- missing-source explanations
- connector and processing state

Core model types must not add fields for original file bytes, raw notification text, raw message text, raw local-file content, raw calendar records, raw usage event dumps, or any raw content blob.

## Local Store Rule

`LocalMemoryStore` accepts only source references, derived memory events, citations, daily summaries, place clusters, app usage summaries, connector-scoped delete requests, and index invalidation requests.

It must never add a method that accepts original file bytes, raw notification text, raw message text, raw local-file content, raw calendar records, raw usage event dumps, or raw source payloads.

The SQLCipher indexing queue may persist only connector/command metadata, date-range bounds, timestamps, leases, attempt/count fields, and stable skip/failure codes. It must never persist connector output, exception messages, source URIs, or source-derived text.

The shared command executor catches connector/store failures only to select those stable codes. It does not persist exception messages, scan results, missing-source prose, or provider response bodies in the queue. The derived scan itself is written only by the SQLCipher store after an atomic live-lease check; it is never serialized into the queue. Cancellation is propagated and recovered through lease metadata rather than serialized error details.
