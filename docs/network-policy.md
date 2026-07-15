# Network Policy

Grayin AI is local-first, not offline-only. The app may use the network only when a feature requires a bounded external API or a fixed artifact download.

Encrypted derived-memory export/import is outside Grayin's app-owned network boundary. Its Android document contracts set `EXTRA_LOCAL_ONLY`, which asks the system picker to return data already on the device, take no persistent backup URI grant, and provide no Grayin network client, automatic transfer, or sync path. The selected `DocumentsProvider` is a separate Android component responsible for honoring that request; Grayin cannot cryptographically prove how another provider stores or later syncs a chosen document. The UI therefore tells the user to choose an on-device location. A Grayin-owned remote/cloud provider or backup transport would be a new network capability and is forbidden without a separate policy change.

## Allowed Network Boundaries

### Typed External Enrichment

External map, place, reverse-geocode, and weather providers may be called only through typed methods owned by `OnlineEnrichmentGateway`.

Allowed request inputs are an ephemeral `EnrichmentRequest` projection containing only minimal lookup values required by the provider, such as:

- rounded latitude and longitude
- observation timestamp
- locale, language, or measurement units
- coarse place or region query when a typed method explicitly requires it

Provider base URLs, paths, and request schemas must be fixed in provider code or trusted build configuration. Feature code, connectors, local models, and users must not supply arbitrary URLs or endpoint paths.

An `EnrichmentRequest` is not a persisted `DerivedMemoryEvent` and must not contain summaries, labels, entities, citations, source references, evidence IDs, or connector payloads.

Current fixed providers and projections:

- reverse geocode: Android `Geocoder`, only after separate online-enrichment opt-in; latitude/longitude are rounded to 0.001 degrees and only locality, sub-locality, region, sub-region, and two-letter country code may return to the app
- weather: Open-Meteo forecast and archive APIs, only after the same opt-in; latitude/longitude are rounded to 0.01 degrees and only one UTC date is transmitted
- weather hosts are fixed to `api.open-meteo.com` and `archive-api.open-meteo.com`; paths and query keys are fixed in provider code, redirects are rejected, cleartext traffic is disabled, connect/read timeouts are 5 seconds each, and the response body is capped at 64 KiB

A connected Location scan calls reverse geocoding and weather only while the separate enrichment preference is enabled. The two typed calls are independent: unavailable results or sanitized provider failures remove only that result and cannot prevent coordinate-only local indexing or the other enrichment. Cancellation is propagated. Persisted weather signals are reconstructed from validated WMO code, temperature, and precipitation numbers; provider labels, attribution URLs, response bodies, and exception text are not copied into derived memory.

Open-Meteo's public endpoint is for non-commercial prototype use. Its API data requires CC BY 4.0 attribution, and its published terms say request logs that can include IP addresses and URLs may be retained for 90 days. A commercial release requires a paid/fixed provider contract and must not ship a secret API key as if an APK could protect it.

### Fixed-Catalog Artifact Downloads

Runtime model files, authenticated model manifests, and on-device OCR language data may be downloaded only through fixed catalog infrastructure. Model files and OCR language data are immutable artifacts: each entry must define an immutable HTTPS URL without user information, custom port, query, or fragment; a safe file identity; exact byte count; pinned SHA-256 digest; and license or terms URL. A remotely updated manifest is intentionally mutable, so it cannot also pin its own future byte count and digest. Instead it must come from one fixed HTTPS endpoint, reject redirects and oversized or non-canonical JSON, and carry an ECDSA P-256 signature over its exact canonical payload. The app verifies that signature with a bundled public key, enforces a bounded validity window and explicit app/runtime compatibility, and durably rejects sequence rollback or same-sequence equivocation. A bundled catalog is authenticated by the signed APK and must still pin artifact digests. The bundled OCR catalog is versioned with the signed APK. Missing or invalid required configuration disables network access before a connection is opened.

Users may import a local model file, but they must not enter a remote model URL. Downloaded files stay in app-private storage. Model inference remains local and does not use the network.

OCR language data is limited to the app-bundled `eng`, `kor`, and `jpn` catalog at immutable `tessdata_fast` commit `87416418657359cb625c412a48b6e1d6d41c29bd`. Each entry pins the exact raw GitHub path, byte count, SHA-256 digest, safe file name, and Apache-2.0 license URL. A user must tap Download in Settings for each language. Document selection, manual indexing, and automatic indexing must never enqueue a language-data download. Work input contains only the language-pack ID and local generation; it contains no URL, URI, document identity, source data, query, or evidence. Installed data stays under `noBackupFilesDir` and OCR inference is network-free.

All immutable fixed artifacts reject redirects, non-HTTPS endpoints, unexpected content type or encoding, response bodies larger or smaller than the exact catalog byte count, checksum mismatch, and non-atomic publication. Signed manifests use a fixed HTTPS endpoint without user information, custom port, query, or fragment; redirect following is disabled; only identity-encoded JSON within the 64 KiB envelope limit is read. Missing, negative, oversized, or chunked body lengths cannot allocate beyond that cap, and malformed declared lengths return a stable size failure. Verification then rejects non-canonical envelopes/payloads, untrusted keys, invalid signatures, unsafe or unsupported catalog entries, incompatible/expired metadata, rollback, and equivocation. The accepted sequence, payload digest, canonical payload, signature key ID, and normalized P-256 public-key SHA-256 fingerprint are committed together. Corrupt or partially bound durable state fails closed.

An accepted manifest can project release metadata only onto the explicitly allowed Grayin dedicated model identity. Before every stored projection, the app reruns the current schema, time, app/runtime/container, filename, URL, size, hash, and license policy. It also requires the stored trust identity to match the currently configured, structurally valid fixed endpoint and P-256 key; key rotation hides the old payload until a manifest verifies under the new key. Normal snapshots do not refresh the network manifest. Entering Settings uses a process-local monotonic 15-minute refresh gate shared across controller recreation, while `ModelDownloadWorker` checks its durable generation before any manifest transport, forces one refresh, and checks again after release projection; a newer release therefore fences both stale manifest transport and artifact work. Verified releases use digest-specific app-private paths, so transport reconfiguration and failed replacement keep the last verified model usable until a new verified staging file is atomically published and its metadata commit succeeds. Every fixed-catalog model path component below `filesDir` is checked without following symbolic links before staging, verification, movement, or deletion. Deletion walks do not follow links, and verified-file cache hits require stable file-key and change-time identity; unsupported identity metadata falls back to a full SHA-256 pass.

The bundled model catalog has no transport entry with a complete immutable URL, exact byte count, and digest. The bounded fetch, signature verification, durable acceptance, active-catalog projection, and worker integration are implemented, but production `endpointUrl` and P-256 public key are intentionally `null`. Refresh therefore returns `NOT_CONFIGURED` before opening a connection. Non-null but malformed endpoint/key material is also treated as invalid, and the catalog repository refuses to project any residual accepted payload unless the full current trust configuration validates. Model download actions remain unavailable, and `ModelDownloadWorker` fails before artifact transport. Official model pages and local document import remain available. A model network entry may be enabled only after release metadata, production trust configuration, and review are complete.

## Forbidden Network Use

Grayin AI must not:

- transmit raw/original source data
- transmit stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, or any field outside the approved ephemeral `EnrichmentRequest` projection
- call a remote or commercial LLM
- expose arbitrary or user-supplied URLs/endpoints
- add account, cloud sync, server backup, remote storage, analytics, ads, telemetry, or crash reporting
- let connectors call network clients directly
- let a local model or model-generated output invoke external APIs

## Ownership and Enforcement

- `OnlineEnrichmentGateway` owns external enrichment calls.
- Fixed artifact infrastructure owns model, manifest, and OCR language-data calls.
- Connectors may request enrichment only through the typed gateway and may pass only approved derived lookup inputs.
- UI and feature code may select a typed operation or catalog item, never an endpoint.
- Each new network capability requires an explicit typed contract, fixed provider configuration, privacy review, tests, UI disclosure, and updates to this document before implementation.

## Failure Behavior

Network failure must not block local indexing, retrieval, or template answers. Enrichment failures return an explicit unavailable result. Model download failures keep the last verified local model or template fallback. OCR download failures keep any last verified language data; PDF pages that require a missing pack return a typed local missing-data result.

No network response may be treated as trusted until HTTP status, content type, schema, array length, timestamp, numeric range, size, and integrity checks succeed. Provider response bodies and exception messages must not be copied into UI errors or logs.
