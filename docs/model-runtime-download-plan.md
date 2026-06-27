# Runtime Model Download Plan

## Summary

APK/AAB must not include model weights. Grayin ships the LiteRT-LM runtime only. Users choose a local model at runtime, then download or import a `.litertlm` file into app-private storage.

## Model Options

| Model | Source | Approx size | Status |
| --- | --- | ---: | --- |
| Grayin Gemma 4 E2B Q4 v1 | Grayin file server | 2.3 GB | Catalog placeholder until server URL/checksum exist |
| Gemma 4 E2B | Google AI Edge LiteRT Community on Hugging Face | 2.58 GB | Downloadable |
| Gemma 4 E4B | Google AI Edge LiteRT Community on Hugging Face | 3.65 GB | Downloadable, high-end devices |

## Implementation Todo

- Add model catalog entries with id, label, provider, URL, file name, size, checksum, RAM recommendation, license URL, and recommended flag.
- Add model install state store for selected model, install metadata, download status, progress, checksum, and failure reason.
- Add runtime downloader using WorkManager foreground work.
- Download to app-private `.tmp`, validate size/checksum, then atomically rename to `files/models/{modelId}/model.litertlm`.
- Update resolver to prefer selected ready downloaded model, then imported model, then adb development model, then template fallback.
- Update Settings with model picker, source/size/status rows, download/cancel/delete controls, and existing import fallback.
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
- Implemented Wi-Fi/unmetered WorkManager downloads in `ModelDownloadWorker`.
- Implemented Settings model picker with select, open page, download, cancel, and delete controls.
- `Gemma4ModelPathResolver` prefers the selected ready downloaded model, then legacy import/dev paths.
- Grayin dedicated model remains a disabled catalog placeholder until URL and checksum are available.
- App-specific training scaffold lives in `model-training/`; generated `.litertlm` files remain ignored and must be published separately.
