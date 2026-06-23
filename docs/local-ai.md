# Local AI Adapter

MVP 9 adds local-only language model contracts.

## Interface

`LocalLanguageModel.generate` accepts only an `EvidencePack`.

The interface does not accept source connectors, store handles, files, URIs, notification records, calendar records, app usage dumps, or network clients.

## Implementations

- `Gemma4LocalLanguageModelPlaceholder`: replaceable placeholder for a future on-device Gemma 4 adapter.
- `FakeLocalLanguageModel`: deterministic fake model for local tests and UI wiring.

Both implementations set:

- `localOnly = true`
- `commercialApi = false`
- `networkRequired = false`

## Boundary

No commercial LLM API is configured in MVP.

No network dependency is added for local AI in MVP.

The app may request INTERNET permission for typed weather or reverse-geocode enrichment, but that permission must not be used by local model adapters.

## Current Answer Path

Ask uses `TemplateGroundedAnswerGenerator` over an `EvidencePack` built from SQLCipher-stored derived local-file evidence.

The Gemma placeholder remains unavailable until a real on-device model adapter is added.
