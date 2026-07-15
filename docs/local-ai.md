# Local AI Adapter

MVP 9 added local-only language model contracts. The current app wires Ask to a local Gemma LiteRT-LM adapter when a model file is installed.

## Interface

`LocalLanguageModel.generate` accepts only an `EvidencePack`.

The prompt renderer closes every dynamic line by UTF-8 byte count, removes malformed, control, format, private-use, surrogate, and unassigned Unicode, renders at most 12 evidence items and four citations per item, and verifies the complete accepted prompt remains below 64 KiB. The Python training/evaluation renderer mirrors this contract and golden fixture exactly.

The interface does not accept source connectors, store handles, files, URIs, notification records, calendar records, app usage dumps, or network clients.

## Implementations

- `Gemma4LocalLanguageModel`: on-device LiteRT-LM adapter intended for the official local Gemma 4 E2B `.litertlm` model.
- `FakeLocalLanguageModel`: deterministic fake model for local tests and UI wiring.

Local model implementations set:

- `localOnly = true`
- `commercialApi = false`
- `networkRequired = false`

## Boundary

No commercial LLM API is configured.

The Gemma adapter does not use the network. It only reads an installed local model file and an `EvidencePack`.

Runtime model download is separate from inference. Users cannot enter arbitrary model URLs. A catalog item becomes downloadable only when it has an immutable HTTPS URL, exact byte count, lowercase SHA-256 digest, reviewed license metadata, and durable stale-worker generation fencing. It then uses the shared fixed-artifact verifier and atomic publication rules in `docs/network-policy.md`.

Long-running model transfer uses WorkManager foreground execution. The merged foreground service and each `ForegroundInfo` both declare the `dataSync` service type on supported Android versions, including the Android 14 target-SDK requirement; older supported versions receive type `0`.

No current bundled model entry has complete transport metadata, so every model network download is disabled before a connection opens. A bounded signed-manifest client can supply reviewed release metadata only for the dedicated Grayin identity, but production keeps its fixed endpoint and P-256 public key `null`; refresh therefore returns `NOT_CONFIGURED` without network I/O. General UI snapshots never refresh this endpoint. Settings uses a process-local 15-minute gate, and a model download worker forces a refresh before resolving the release. Accepted data is bound to the signing key ID/public-key fingerprint and revalidated against the current app/runtime/artifact policy before reuse. Settings may open official model pages, and local `.litertlm` import remains available.

App-managed catalog files created by an older build are not trusted from readability or a stored path alone. An installed catalog release is exposed as `READY` only after its stored path, exact size, digest, no-symlink path, and stable file identity are independently reverified. Cache reuse requires both a file key and change time; otherwise the full SHA-256 is recomputed. Staging and cleanup reject symbolic-link ancestors below `filesDir`, and recursive cleanup never follows links. Once verified, a release remains usable when catalog transport metadata changes or a replacement download fails; the old digest-specific release is removed only after a new release atomically publishes and its ready metadata commits. Unverified legacy state is removed. User-imported and debuggable-build-only development paths remain separate.

The app uses INTERNET permission for typed external enrichment and fixed-catalog artifact downloads, but local model adapters must remain network-free. Map or place enrichment goes through `OnlineEnrichmentGateway` with approved derived lookup inputs only, never through model-generated URL calls.

## Model File

The app does not bundle model weights in the APK/AAB.

Settings exposes a runtime model catalog:

- `Grayin Gemma 4 E2B WI8/AFP32 v1`: app-dedicated release identity aligned with the pipeline's explicit `dynamic_wi8_afp32` recipe; transport disabled until an accepted external release and signed metadata exist.
- `Gemma 4 E2B`: official Google AI Edge LiteRT Community information page; direct catalog transport disabled.
- `Gemma 4 E4B`: official higher-memory model information page; direct catalog transport disabled.

Downloaded models are stored in app-private storage:

- `files/models/{modelId}/releases/{sha256}/model.litertlm`

`Gemma4LocalLanguageModel` resolves the selected ready downloaded model first. If no selected download is ready, it falls back to the app-private imported model path:

- app private files: `models/gemma-4-E2B-it.litertlm`

Debuggable builds additionally allow developer-only paths that are excluded from release resolution and release Settings guidance:

- app external files: `models/gemma-4-E2B-it.litertlm`
- adb development path: `/data/local/tmp/grayin/gemma-4-E2B-it.litertlm`

No candidate path is exposed as local-model `READY` from readability alone. Every selected or imported candidate, plus every debuggable-build external-files or adb candidate, must first pass the same bounded LiteRT-LM v1 container validation described below; an invalid candidate is skipped and Ask uses the next verified candidate or the template fallback.

At this path-discovery boundary, `READY` means only that a container is structurally compatible with the pinned LiteRT-LM reader. It does not prove Gemma identity or variant, validate model weights, or prove that the engine can initialize. Engine initialization happens during local generation; any initialization or inference failure remains fail-closed to the template answer path.

## User Model Guide

Users must obtain model weights at runtime because Gemma model access and redistribution are controlled by upstream model terms.

Official source:

- Google AI Edge LiteRT-LM Gemma docs: `https://developers.google.com/edge/litert-lm/models/gemma-4`
- Hugging Face model repo: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm`
- LiteRT-LM 0.13.1 container reader: `https://github.com/google-ai-edge/LiteRT-LM/blob/v0.13.1/schema/core/litertlm_read.cc`

Runtime download after immutable release metadata is configured:

- Open Settings; the app fetches only the configured fixed signed-manifest endpoint and retains the last accepted compatible manifest.
- Pick an available catalog release.
- Tap Download only when Settings reports that the catalog entry is available.
- WorkManager uses Wi-Fi or another unmetered network.
- The worker refreshes the same authenticated catalog, fences metadata changes, and the shared fixed-artifact verifier rejects redirects, unexpected headers, size mismatch, and checksum mismatch before atomic installation into app-private storage.

In the current build, use the official page plus manual import because the Download action is disabled.

Recommended manual import target:

- `gemma-4-E2B-it.litertlm`

Debuggable builds also support developer installation:

```bash
adb shell mkdir -p /data/local/tmp/grayin
adb push gemma-4-E2B-it.litertlm /data/local/tmp/grayin/
```

Settings also supports end-user import. The user can open the official model page, select a compatible `.litertlm` file through Android document picker, and Grayin copies it into app-private `files/models/gemma-4-E2B-it.litertlm`. The destination name is a stable runtime path, not proof that the imported container is Gemma 4 E2B.

Import is fail-closed and accepts only a regular `.litertlm` file from 1 MiB through 8 GiB whose 32-byte preamble has the `LITERTLM` magic, container major version 1, and a header region no larger than 16 KiB with a structurally plausible FlatBuffer root. The size must be known before copying, the app-private filesystem must have room for the full staging file plus a 16 MiB reserve, and copied bytes must exactly match the declared size.

The selected file is copied to a same-directory staging file, flushed to storage, validated again, and published with an atomic replacement only. Grayin never deletes the existing container-verified model before that commit point and has no non-atomic fallback. Validation errors, I/O errors, insufficient space, unsupported atomic moves, and coroutine cancellation delete staging while preserving the previous container-verified model.

Settings can delete downloaded catalog models and app-imported model files. Debug-only development files under `/data/local/tmp/grayin/` remain developer-managed and are neither resolved nor advertised by release builds.

## Current Answer Path

Ask builds an `EvidencePack` from SQLCipher-stored derived indexed evidence, then tries `Gemma4LocalLanguageModel` first.

If the model file is unavailable or generation fails, Ask falls back to `TemplateGroundedAnswerGenerator`.

## App Model Training

App-specific model training assets live under `model-training/`.

The repository tracks only training code, configs, synthetic policy examples, and small metadata. Gemma reference weights, checkpoints, adapters, merged weights, LiteRT exports, private/raw data, and generated output model files are ignored by git.

Training data in this repo must stay synthetic or hand-authored. Real user originals, raw connector payloads, SQLCipher exports, real EvidencePack prompts, and real answers must not be used for training.

Current training target:

- Gemma 4 E2B instruction base pinned as `google/gemma-4-E2B-it@70af34e20bd4b7a91f0de6b22675850c43922a03`.
- Local reference weights under ignored `model-training/reference-models/`.
- LoRA/QLoRA app-behavior tuning for evidence-grounded recall, missing-data honesty, and non-agentic refusal.
- Synthetic training and evaluation JSONL under `model-training/data/synthetic/`.
- Setup validation through `model-training/scripts/validate_training_setup.py`.
- Offline LoRA merge with operator-pinned base/adapter tree hashes and recorded corpus, config, output, and actual tool-version provenance.
- Official LiteRT Torch `export_hf` wrapper with the pinned local Gemma 4 Jinja template, fail-closed staging, bounded LiteRT-LM v1 validation, SHA-256, size, and actual exporter-version provenance.
- Official `litert-lm run` gate over all 30 held-out synthetic prompts. Exactly one four-line grounded answer per prompt and 100% deterministic policy metrics are required; a limited smoke run cannot pass the release gate.
- Ignored local release metadata binds merge, export, model, predictions, evaluation, and catalog-baseline hashes. It does not activate download or replace the separately signed remote manifest.
- Final `.litertlm` output outside git, followed by representative Android-device acceptance and runtime publication through the fixed-catalog model infrastructure after immutable URL, license, catalog identity/quantization label, size, checksum, and signature review.

The reproducible local commands and official upstream links are documented in `model-training/README.md`. `make -f model-training/Makefile release-plan` is safe without weights or LiteRT tools and reports plans only. No actual Grayin model artifact has been produced or accepted by the repository checks until the real merge, export, full CLI gate, and device checks have run.
