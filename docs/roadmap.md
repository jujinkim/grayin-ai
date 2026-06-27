# Roadmap

Current phase status: usable local Text/Markdown MVP is complete.

## Current Capability

- User-selected `.txt` and `.md` files through Android document picker.
- User-invoked Android last-known location samples through runtime location permission.
- User-invoked Android calendar events through runtime calendar read permission.
- User-invoked Android photo metadata through runtime media read permission.
- User-invoked Android app usage summaries through usage-access settings.
- User-invoked Android notification-derived signals through notification-listener settings access.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- First-launch Sources intro explaining that user-invoked sources must be indexed before Ask can use them.
- Sources UI is backed by connector metadata and permission/index state.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- INTERNET permission restricted to typed enrichment methods only: `getWeather` and `reverseGeocode`.

## Future Work

- Implement online weather and reverse-geocode providers behind `OnlineEnrichmentGateway`.
- Add PDF/OCR local-file indexing.
- Add real on-device Gemma adapter.
- Implement encrypted export/import runtime.
- Add optional screenshot blocking and biometric app lock.
