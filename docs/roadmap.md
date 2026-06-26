# Roadmap

Current phase status: usable local Text/Markdown MVP is complete.

## Current Capability

- User-selected `.txt` and `.md` files through Android document picker.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- INTERNET permission restricted to typed enrichment methods only: `getWeather` and `reverseGeocode`.

## Future Work

- Implement real location, photos, calendar, notification, and app-usage connectors.
- Implement online weather and reverse-geocode providers behind `OnlineEnrichmentGateway`.
- Add PDF/OCR local-file indexing.
- Add real on-device Gemma adapter.
- Implement encrypted export/import runtime.
- Add optional screenshot blocking and biometric app lock.
