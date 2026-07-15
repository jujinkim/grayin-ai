# Network Policy

Grayin AI is local-first, not offline-only. The app may use the network only when a feature requires a bounded external API or a fixed artifact download.

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

### Fixed-Catalog Artifact Downloads

Runtime model files and authenticated model manifests may be downloaded from fixed catalog entries. Every model artifact must have a pinned SHA-256 digest. A remotely updated manifest must carry an ECDSA P-256 signature verified with a public key bundled in the app; a bundled catalog is authenticated by the signed APK and must still pin artifact digests. Catalog entries must define an HTTPS URL, expected file identity, size limits, license or terms URL, and app compatibility metadata.

Users may import a local model file, but they must not enter a remote model URL. Downloaded files stay in app-private storage. Model inference remains local and does not use the network.

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
- Model download infrastructure owns fixed-catalog artifact calls.
- Connectors may request enrichment only through the typed gateway and may pass only approved derived lookup inputs.
- UI and feature code may select a typed operation or catalog item, never an endpoint.
- Each new network capability requires an explicit typed contract, fixed provider configuration, privacy review, tests, UI disclosure, and updates to this document before implementation.

## Failure Behavior

Network failure must not block local indexing, retrieval, or template answers. Enrichment failures return an explicit unavailable result. Model download failures keep the last verified local model or template fallback.

No network response may be treated as trusted until schema, size, and integrity checks succeed.
