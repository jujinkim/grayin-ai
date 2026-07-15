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
- real Local Files connector for user-selected Text, Markdown, and PDF documents with on-device page/OCR indexing
- real basic connectors for location, photos, calendar, notifications, and app usage, including encrypted rounded-coordinate place-cluster accumulation
- SQLCipher-backed indexing queue with leased manual execution and WorkManager automatic scheduling
- manual 7-, 30-, and 90-day indexing for Calendar, Photos, and App Usage
- typed multilingual time/capability query planning and scoped evidence retrieval
- cited grounded-answer format with strict missing-data and confidence validation
- on-device Gemma LiteRT-LM adapter with verified local import, implemented signed-manifest/download infrastructure, and template fallback; no production model release is currently configured
- INTERNET permission for typed map/place/reverse-geocode/weather enrichment and fixed-catalog model, manifest, or OCR language-data downloads
- default-OFF bounded Android reverse geocoding and fixed Open-Meteo weather provider with explicit provider disclosure
- authenticated encrypted derived-memory export/import with exact schema-v8 validation and connector re-consent
- optional screenshot blocking and biometric/device-credential app lock
- reproducible local model merge/export/evaluation pipeline with an exact 30-fixture release contract
- documentation
- benchmark questions
- typed English/Korean/Japanese presentation for connector status, Timeline events, and Places clusters

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
- photo pixel understanding or visual-content clusters
- an Android historical-location archive beyond samples explicitly observed by Grayin scans
- calls, messages, browser history, audio, or video connectors

## Remaining Work

`docs/remaining-work.md` is the single source of truth for external release blockers, physical-device acceptance, release quality, and deferred product scope. This file defines MVP inclusion and exclusion only; do not duplicate the open queue here.

## MVP Connectors

- Location
- Photos
- Calendar
- Notification-derived events
- App Usage
- Local Files / Markdown / PDF pages
