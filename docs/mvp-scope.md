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

## External Release Gates

- select a commercial-compatible enrichment provider or contract for production use
- train, independently review, merge, and export the actual model in a hermetic release environment
- pass all 30 model-output fixtures plus representative-device quality, latency, and memory acceptance
- publish the reviewed artifact at an immutable HTTPS URL with final license, exact size, and SHA-256
- configure production P-256 signing-key custody and the fixed signed-manifest endpoint

## Physical-Device Acceptance Gates

- Android permission/settings and partial-photo reselection flows
- SAF persistence/revocation, PDF embedded-text/OCR, cancellation, timeout, and document-process death
- WorkManager constraints, notification-listener delivery, biometric/device credential, and `FLAG_SECURE` across the supported API matrix
- real LiteRT-LM engine initialization and inference on representative devices

## MVP Connectors

- Location
- Photos
- Calendar
- Notification-derived events
- App Usage
- Local Files / Markdown / PDF pages
