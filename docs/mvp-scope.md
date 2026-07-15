# MVP Scope

## Included

- Android-only native app
- Kotlin
- Jetpack Compose
- Ask-first UI
- language settings for system, Korean, English, and Japanese
- icon-labeled bottom navigation
- SQLCipher-backed derived-memory store
- connector abstraction
- real Local Files connector for user-selected `.txt` / `.md` documents
- real basic connectors for location, photos, calendar, notifications, and app usage
- indexing queue abstraction
- query planner skeleton
- grounded answer format
- local LLM adapter abstraction
- INTERNET permission for typed map/place/reverse-geocode/weather enrichment and fixed-catalog model, manifest, or OCR language-data downloads
- default-OFF bounded Android reverse geocoding and fixed Open-Meteo weather provider with explicit provider disclosure
- documentation
- benchmark questions

## Excluded

- iOS
- cloud sync
- application backend
- account
- commercial LLM API
- network use outside approved enrichment and artifact-download boundaries
- arbitrary URL or endpoint calls from app feature code
- ads
- analytics
- crash SDK
- agentic action APIs
- bundled offline map data
- direct or arbitrary map SDK/API access outside `OnlineEnrichmentGateway`
- raw data storage

## MVP Connectors

- Location
- Photos
- Calendar
- Notification-derived events
- App Usage
- Local Files / Markdown
