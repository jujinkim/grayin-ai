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
- stub connectors for location, photos, calendar, notifications, and app usage
- indexing queue abstraction
- query planner skeleton
- grounded answer format
- local LLM adapter abstraction
- INTERNET permission for typed weather/reverse-geocode enrichment
- documentation
- benchmark questions

## Excluded

- iOS
- cloud sync
- server
- account
- commercial LLM API
- broad background networking
- arbitrary URL or endpoint calls from app feature code
- ads
- analytics
- crash SDK
- agentic action APIs
- offline map data
- online map SDK
- raw data storage

## MVP Connectors

- Location
- Photos
- Calendar
- Notification-derived events
- App Usage
- Local Files / Markdown
