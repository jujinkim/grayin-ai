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

The Gemma adapter does not use the network. It only reads an installed local model file and an `EvidencePack`.

Runtime model download is separate from inference. It is limited to fixed catalog URLs for official Gemma LiteRT Community models and the future Grayin model server entry. Users cannot enter arbitrary model URLs.

The app may request INTERNET permission for typed weather or reverse-geocode enrichment, but that permission must not be used by local model adapters. Map or place inference goes through `OnlineEnrichmentGateway.reverseGeocode` with derived coordinates only, not through arbitrary URL calls.

## Model File

The app does not bundle model weights in the APK/AAB.

Settings exposes a runtime model catalog:

- `Grayin Gemma 4 E2B Q4 v1`: app-dedicated model placeholder; disabled until Grayin file-server URL and checksum exist.
- `Gemma 4 E2B`: Google AI Edge LiteRT Community model hosted on Hugging Face.
- `Gemma 4 E4B`: Google AI Edge LiteRT Community model hosted on Hugging Face for higher-end devices.

Downloaded models are stored in app-private storage:

- `files/models/{modelId}/model.litertlm`

`Gemma4LocalLanguageModel` resolves the selected ready downloaded model first. If no selected download is ready, it falls back to legacy manual install paths:

- app private files: `models/gemma-4-E2B-it.litertlm`
- app external files: `models/gemma-4-E2B-it.litertlm`
- adb development path: `/data/local/tmp/grayin/gemma-4-E2B-it.litertlm`

## User Model Guide

Users must obtain model weights at runtime because Gemma model access and redistribution are controlled by upstream model terms.

Official source:

- Google AI Edge LiteRT-LM Gemma docs: `https://developers.google.com/edge/litert-lm/models/gemma-4`
- Hugging Face model repo: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`

Runtime download:

- Open Settings.
- Pick `Gemma 4 E2B` or `Gemma 4 E4B`.
- Tap Download.
- WorkManager downloads only on Wi-Fi or another unmetered network.
- The model is copied to app-private storage and becomes the selected local model.

Manual import fallback expected file:

- `gemma-4-E2B-it.litertlm`

Current build supports developer installation:

```bash
adb shell mkdir -p /data/local/tmp/grayin
adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/
```

Settings also supports end-user import. The user can open the official model page, select a `.litertlm` file through Android document picker, and Grayin copies it into app-private `files/models/gemma-4-E2B-it.litertlm`.

Import rejects files without a `.litertlm` extension and files smaller than 1 MB.

Settings can delete downloaded catalog models and app-imported model files. Development files under `/data/local/tmp/grayin/` remain developer-managed.

## Current Answer Path

Ask builds an `EvidencePack` from SQLCipher-stored derived indexed evidence, then tries `Gemma4LocalLanguageModel` first.

If the model file is unavailable or generation fails, Ask falls back to `TemplateGroundedAnswerGenerator`.
