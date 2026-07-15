# Runtime Model Download Plan

## Summary

APK/AAB must not include model weights. Grayin ships the LiteRT-LM runtime only. Users choose a local model at runtime, then download or import a `.litertlm` file into app-private storage.

Model downloads use the fixed-artifact network boundary in `docs/network-policy.md`. They must use immutable catalog URLs and must never include user-memory data. Current entries are deliberately disabled because no reviewed model artifact has a complete immutable URL, exact byte count, and SHA-256 digest. Durable model-specific generation fencing is implemented, so adding complete reviewed metadata will not bypass stale-worker protection.

## Model Options

| Model | Source | Approx size | Status |
| --- | --- | ---: | --- |
| Grayin Gemma 4 E2B WI8/AFP32 v1 | External host not selected | TBD | Dedicated release identity using the explicit `dynamic_wi8_afp32` pipeline; transport disabled pending the accepted artifact, URL, license, size, checksum, and signed manifest |
| Gemma 4 E2B | Google AI Edge LiteRT Community on Hugging Face | 2.58 GB | Official page only; network download disabled pending exact metadata |
| Gemma 4 E4B | Google AI Edge LiteRT Community on Hugging Face | 3.65 GB | Official page only; network download disabled pending exact metadata |

## Implemented Repository Path

- Model catalog entries carry id, label, provider, optional immutable URL, file name, exact size, checksum, RAM recommendation, license URL, and recommended flag.
- Model install state carries the selected model, durable generation, transport-identity fingerprint, install metadata, download status, progress, checksum, and stable failure reason.
- WorkManager foreground orchestration stays behind the shared fixed-artifact verifier.
- Work input contains only model ID and durable generation. Enqueue, replacement, cancellation, deletion, and transport-identity reconfiguration advance the generation before older work can open the network or update state.
- Downloads use an app-private same-filesystem `{generation}.{workerId}.part`, validate headers/exact size/checksum, flush, recheck the generation, and atomically publish to `files/models/{modelId}/releases/{sha256}/model.litertlm`.
- The resolver prefers the selected ready downloaded model, then the imported model, then debuggable-build-only development paths, then template fallback.
- Settings includes model selection, source/size/status rows, conditional download/cancel/delete controls, and local import fallback.
- The signed-manifest client uses a fixed 64 KiB HTTPS endpoint, canonical P-256 verification, compatibility/expiry checks, durable rollback/equivocation state, current-policy projection, and a process-wide Settings refresh gate.
- Local release tooling performs pinned-input LoRA merge, explicit WI8/AFP32 export, exact 30-fixture CLI evaluation, and provenance generation outside git.

## External Activation Requirements

- Set up Grayin dedicated model hosting outside git, preferably object storage/CDN rather than GitHub Pages, with immutable release URLs and no arbitrary user-entered endpoints.
- Publish a release manifest for the Grayin dedicated model with model id, version, file name, byte size, SHA-256 checksum, license/terms URL, minimum app/runtime version, container major version, bounded validity, monotonic sequence, and replacement/deprecation metadata. Sign the exact canonical payload with an external ECDSA P-256 private key and verify it with the reviewed public key bundled in the app. The mutable manifest uses one fixed bounded endpoint rather than pretending that future manifest bytes can have an APK-pinned digest.
- Keep Grayin dedicated model catalog entry disabled until server URL/checksum are configured.
- Keep user data out of model training and out of downloaded model state.
- Keep Gemma reference weights and generated model outputs outside git under `model-training/` ignore rules.

## Verification

- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:compileDebugKotlin`
- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:testDebugUnitTest`
- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:assembleDebug`
- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:lintDebug`
- `git diff --check`

## Implementation Notes

- Implemented model catalog in `ModelCatalog`.
- Implemented app-private install state in `ModelInstallStore`.
- Implemented Wi-Fi/unmetered and storage-not-low WorkManager orchestration in `ModelDownloadWorker`, routed through the shared fixed-artifact verifier.
- Implemented synchronous durable generation fencing across enqueue/replacement, cancellation, deletion, transport reconfiguration, manifest-network start, post-refresh projection, artifact progress, retry, failure, and atomic publication. Worker-specific staging prevents replacement workers from sharing a partial file, and stale work resolves without opening manifest transport or changing the verified model or current status. Digest-specific release paths retain the last independently verified model through metadata changes or failed replacement and retire it only after the successor publishes and commits. Model storage rejects symbolic links in every existing app-private path component, cleanup walks without following links, and cached digest verification is bound to stable file-key/change-time identity with full-hash fallback.
- Implemented Settings model picker with select/open-page controls and conditional download/cancel/delete actions. The conditional actions are currently hidden because every transport entry is fail-closed.
- `Gemma4ModelPathResolver` prefers the selected ready downloaded model, then the app-private imported model; external-files and adb paths plus their Settings hint are debuggable-build-only.
- Every current model transport entry remains disabled until an immutable URL, exact byte count, and checksum are available. Official page links and local import remain enabled.
- App-specific training and local release tooling lives in `model-training/`; generated weights, `.litertlm`, provenance, predictions, summaries, and local manifests remain ignored and must be published separately.
- The local release path pins base/adapter tree hashes, merges LoRA offline, runs the official `litert-torch export_hf` command with a pinned local Gemma 4 chat template, records the actual exporter version and output SHA-256/size, and gates all 30 synthetic prompts through `litert-lm run` plus the deterministic grounded-answer scorer.
- The exporter command explicitly passes `--quantization_recipe=dynamic_wi8_afp32`, and the disabled Grayin catalog uses the matching `WI8/AFP32` wording and identity. Exact size, digest, immutable URL, license, and final device-validated artifact provenance remain required before transport is configured.
- `write_export_manifest.py` creates ignored local provenance only after the full CLI gate. It does not sign, host, or activate a model. Representative Android-device acceptance, immutable URL/license review, catalog update, and the external-key signed remote manifest remain separate release steps.
- Implemented a strict canonical signed-manifest schema, fixed-endpoint 64 KiB JSON fetch, P-256 verification, bounded validity and entry validation, durable canonical-payload/sequence/trust-identity rollback and equivocation state, current-policy revalidation before stored projection, allowlisted active-catalog projection, gated Settings refresh, and forced worker refresh. The matching release script refuses a private key inside the repository and verifies its signature before writing an envelope.
- Production manifest activation remains disabled because both the reviewed public key and fixed endpoint are `null`. The client returns `NOT_CONFIGURED` before opening a connection, invalid non-null trust material cannot enable residual state, and key rotation requires a newly verified manifest. Test keys exist only in test-generated memory and are never packaged.
