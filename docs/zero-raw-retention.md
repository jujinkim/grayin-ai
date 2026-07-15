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

Fixed OCR `.traineddata` files are public runtime artifacts, not user originals. Their WorkManager input, progress, and install state may contain only catalog pack ID, local generation, bounded progress, byte count, and stable status/failure enums. They must not contain a document URI or name, source content, extracted text, OCR transcript, query, evidence, provider body, or exception message. Verified packs live under app-private `noBackupFilesDir/ocr/tesseract/tessdata`; same-filesystem staging parts are deleted after failure or cancellation and are never backed up. Selecting or indexing a document never starts an artifact download.

PDF parsing and OCR run in the private `:document` process. Binder input is limited to a random request ID and duplicated `ParcelFileDescriptor`; Binder output is limited to bounded page numbers, structural counts, derived keyword signals, and fixed codes. The service never returns raw text, OCR transcripts, images, document identity, source references, or exception text. Main-process code must discard the entire in-memory result on Binder death, timeout, cancellation, or failed validation and may commit only a terminal validated result.

Local Files preferences persist only full domain-separated Android Keystore HMAC markers for selected documents. They do not persist a URI, path, display name, MIME, provider ID, or unkeyed document hash. At scan time the connector transiently HMACs Android's current persisted SAF grants and matches only selected markers. Text/Markdown and each PDF page receive HMAC-only source references; citations are restricted to generic text/Markdown labels or `PDF page N`. SQLCipher schema v6 deletes legacy Local Files graphs and statuses that could contain a URI, unkeyed hash, or file-name citation before reindexing.

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
- citation metadata
- confidence
- derived memory events
- the reserved daily-summary table/wire slot only as an empty schema-v8 section; v8 has no canonical producer and purges all legacy rows
- the reserved app-usage-summary table/wire slot only as an empty schema-v8 section; canonical App Usage data is stored as per-session events and v8 purges all legacy aggregate rows

All derived data is still sensitive and remains encrypted in the SQLCipher store.

Explicit encrypted export may copy only the validated derived-memory snapshot described in `docs/export-import.md`. It clears every local source pointer before encoding, requires the reserved `dailySummaries` and `appUsageSummaries` arrays to be empty, and excludes originals, connector grants/settings, key material, and runtime state. Password-derived AES-GCM ciphertext may be staged under `noBackupFilesDir`; plaintext staging is forbidden. This derived-data exception does not change the rule that raw originals are never exported.

## Engineering Rule

There must be no store API that accepts raw content.

Connectors may read raw source data only inside connector-owned transient processing scopes. They may emit only derived events and source references.

Provider-authored metadata must cross a source-specific canonicalization boundary before derived storage. Calendar title/location and app labels are single-line, control/format-free, and UTF-8 bounded; calendar display/account names are not projected and the source-app marker is fixed. Android location provider names are reduced to a closed provider enum. Photos does not persist MediaStore file names and admits only closed MIME/dimension signals. Notification title/text is capped at 4 KiB before classification, and an oversized arrival is discarded rather than classified from a truncated prefix; notification categories are mapped to a closed set. Weather persistence uses only validated numeric signals and never provider prose, URLs, bodies, or errors.

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

`LocalMemoryStore` accepts only source references, derived memory events, citations, place clusters, connector-scoped delete requests, index invalidation requests, and the two reserved aggregate lists only when empty. Its compatibility snapshot type still carries daily-summary and app-usage-summary lists, but schema v8 requires both to be empty at the shared scan/transfer/store boundary and deletes every legacy row from both aggregate tables during migration.

It must never add a method that accepts original file bytes, raw notification text, raw message text, raw local-file content, raw calendar records, raw usage event dumps, or raw source payloads.

The SQLCipher indexing queue may persist only connector/command metadata, date-range bounds, timestamps, leases, attempt/count fields, and stable skip/failure codes. It must never persist connector output, exception messages, source URIs, or source-derived text.

The shared command executor catches connector/store failures only to select those stable codes. It does not persist exception messages, scan results, missing-source prose, or provider response bodies in the queue. The derived scan itself is written only by the SQLCipher store after an atomic live-lease check; it is never serialized into the queue. Cancellation is propagated and recovered through lease metadata rather than serialized error details.

WorkManager input and progress data contain no source data. The automatic worker receives no raw payload and reconstructs work only from encrypted task metadata, current connector consent, and live device conditions. Automatic runtime status stores enums, timestamps, and counts only.

The encrypted automatic-control metadata contains only an enabled flag, monotonic generation, and a deterministic key for indexing preferences. Queue/runtime generations are coordination numbers; they contain no source content, URI, provider response, location, or user query.

The Sources status card reads only bounded queue/runtime metadata: connector display name, trigger/state, timestamps, counts, and stable reason codes. It never renders task IDs, lease owners, exception text, source pointers, or derived/source content.

The encrypted latest connector scan status may contain only connector ID, processing enum, scan timestamp, optional scan-range timestamps, capability/availability enums, connector ID, and a fixed issue-code storage key. It never stores an explanation string. Schema v5 maps recognized legacy fixed explanations to issue codes and replaces unknown legacy prose with `source_unavailable`. UI and Ask text is generated from the code after reading. Status must not contain a file name, URI, parser exception, extracted text, OCR transcript, provider body, or raw source detail.

Deleting or revoking connector data changes pending and running tasks for that connector to a stable terminal skip state and clears their leases in the same SQLCipher transaction that deletes the graph. An already running reader therefore cannot publish its result after user deletion. A global Local Files timeout or coroutine cancellation publishes no partial replacement; the prior encrypted snapshot remains intact until a terminal scan is available.
