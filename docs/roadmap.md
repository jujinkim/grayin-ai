# Roadmap

Current phase status: usable local Text/Markdown MVP is complete.

## Current Capability

- User-selected `.txt` and `.md` files through Android document picker.
- User-connected Android last-known location samples through runtime location permission.
- User-connected Android calendar events through runtime calendar read permission.
- User-connected Android photo metadata through runtime media read permission.
- User-connected Android app usage summaries through usage-access settings.
- User-connected Android notification-derived signals through notification-listener settings access.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- Local Gemma LiteRT-LM answer adapter over retrieved `EvidencePack`, with template fallback when the model file is unavailable.
- First-launch Sources intro explaining that user-connected sources must be indexed before Ask can use them.
- Sources UI is backed by connector metadata and permission/index state.
- Sources UI exposes top-level Index all now and persisted automatic indexing settings.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- Settings shows local Gemma model status, official model source, file name, current adb install path, and `.litertlm` import/delete controls.
- INTERNET permission restricted to typed enrichment methods only: `getWeather` and `reverseGeocode`; location indexing can use Android reverse geocoding through that boundary.

## Future Work

- Implement online weather provider behind `OnlineEnrichmentGateway`.
- Add PDF/OCR local-file indexing.
- Add signed model integrity checks for imported local Gemma weights.
- Implement encrypted export/import runtime.
- Add optional screenshot blocking and biometric app lock.
