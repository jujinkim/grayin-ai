# Runtime Model Download Plan

## Summary

APK/AAB must not include model weights. Grayin ships the LiteRT-LM runtime only. Users choose a local model at runtime, then download or import a `.litertlm` file into app-private storage.

Model downloads use the fixed-artifact network boundary in `docs/network-policy.md`. They must use immutable catalog URLs and must never include user-memory data. Current entries are deliberately disabled because no reviewed model artifact has a complete immutable URL, exact byte count, and SHA-256 digest, and the model-specific generation fence is not implemented yet.

## Model Options

| Model | Source | Approx size | Status |
| --- | --- | ---: | --- |
| Grayin Gemma 4 E2B Q4 v1 | Grayin file server | 2.3 GB | Catalog placeholder until server URL/checksum exist |
| Gemma 4 E2B | Google AI Edge LiteRT Community on Hugging Face | 2.58 GB | Official page only; network download disabled pending exact metadata |
| Gemma 4 E4B | Google AI Edge LiteRT Community on Hugging Face | 3.65 GB | Official page only; network download disabled pending exact metadata |

## Implementation Todo

- Keep model catalog entries with id, label, provider, optional immutable URL, file name, exact size, checksum, RAM recommendation, license URL, and recommended flag.
- Keep model install state for selected model, install metadata, download status, progress, checksum, and stable failure reason.
- Keep WorkManager foreground orchestration behind the shared fixed-artifact verifier.
- Download to an app-private same-filesystem `.tmp`, validate headers/exact size/checksum, flush, then atomically publish to `files/models/{modelId}/model.litertlm`.
- Update resolver to prefer selected ready downloaded model, then imported model, then adb development model, then template fallback.
- Update Settings with model picker, source/size/status rows, download/cancel/delete controls, and existing import fallback.
- Set up Grayin dedicated model hosting outside git, preferably object storage/CDN rather than GitHub Pages, with immutable release URLs and no arbitrary user-entered endpoints.
- Publish a release manifest for the Grayin dedicated model with model id, version, file name, byte size, SHA-256 checksum, license/terms URL, minimum app version, and rollback/deprecation metadata. Sign remotely updated manifests with ECDSA P-256 and verify them with the public key bundled in the app.
- Keep Grayin dedicated model catalog entry disabled until server URL/checksum are configured.
- Keep user data out of model training and out of downloaded model state.
- Keep Gemma reference weights and generated model outputs outside git under `model-training/` ignore rules.

## Verification

- `cmd.exe /C gradlew.bat --no-daemon :app:clean :app:compileDebugKotlin`
- `cmd.exe /C gradlew.bat --no-daemon :app:testDebugUnitTest`
- `cmd.exe /C gradlew.bat --no-daemon :app:assembleDebug`
- `cmd.exe /C gradlew.bat --no-daemon :app:lintDebug`
- `git diff --check`

## Implementation Notes

- Implemented model catalog in `ModelCatalog`.
- Implemented app-private install state in `ModelInstallStore`.
- Implemented Wi-Fi/unmetered WorkManager orchestration in `ModelDownloadWorker`, routed through the shared fixed-artifact verifier.
- Implemented Settings model picker with select/open-page controls and conditional download/cancel/delete actions. The conditional actions are currently hidden because every transport entry is fail-closed.
- `Gemma4ModelPathResolver` prefers the selected ready downloaded model, then legacy import/dev paths.
- Every current model transport entry remains disabled until an immutable URL, exact byte count, and checksum are available. Official page links and local import remain enabled.
- App-specific training scaffold lives in `model-training/`; generated `.litertlm` files remain ignored and must be published separately.
