# Local AI Adapter

MVP 9 added local-only language model contracts. The current app wires Ask to a local Gemma LiteRT-LM adapter when a model file is installed.

## Interface

`LocalLanguageModel.generate` accepts only an `EvidencePack`.

The interface does not accept source connectors, store handles, files, URIs, notification records, calendar records, app usage dumps, or network clients.

## Implementations

- `Gemma4LocalLanguageModel`: on-device LiteRT-LM adapter for a local Gemma 4 E2B `.litertlm` model file.
- `FakeLocalLanguageModel`: deterministic fake model for local tests and UI wiring.

Local model implementations set:

- `localOnly = true`
- `commercialApi = false`
- `networkRequired = false`

## Boundary

No commercial LLM API is configured.

No network dependency is added for local AI. The Gemma adapter only reads an installed local model file and an `EvidencePack`.

The app may request INTERNET permission for typed weather or reverse-geocode enrichment, but that permission must not be used by local model adapters. Map or place inference goes through `OnlineEnrichmentGateway.reverseGeocode` with derived coordinates only, not through arbitrary URL calls.

## Model File

The app does not download or bundle model weights.

`Gemma4LocalLanguageModel` becomes ready when `gemma-4-E2B-it.litertlm` exists at one of these paths:

- app private files: `models/gemma-4-E2B-it.litertlm`
- app external files: `models/gemma-4-E2B-it.litertlm`
- adb development path: `/data/local/tmp/grayin/gemma-4-E2B-it.litertlm`

## Current Answer Path

Ask builds an `EvidencePack` from SQLCipher-stored derived indexed evidence, then tries `Gemma4LocalLanguageModel` first.

If the model file is unavailable or generation fails, Ask falls back to `TemplateGroundedAnswerGenerator`.
