# Grayin AI

Grayin AI is a local-first Android memory indexer that helps you recall your life without storing your original data.

> Your data stays where it is. Your memory becomes searchable.

## Philosophy

AI is becoming our arms and legs. Grayin AI is different: it helps humans see, remember, and decide better.

Grayin AI is non-agentic. It does not act on behalf of the user. It helps the user recall local, indexed evidence.

## Principles

- No server
- No account
- No cloud in MVP
- No network permission in MVP
- No raw data retention
- No commercial LLM API in MVP
- No agentic actions
- User-enabled sources only
- Local indexed evidence only
- Open source

## What It Does

Grayin AI indexes user-enabled local Android data sources into derived memory events.

Current usable MVP path:

- user selects `.txt` or `.md` files with the Android document picker
- connector reads file text transiently during indexing
- app stores only source references, keyword signals, summaries, citations, and confidence
- SQLCipher encrypts the local derived-memory store
- Android Keystore protects the generated SQLCipher passphrase
- Ask answers from indexed, cited local evidence and lists missing sources

Examples:

- location clusters
- photo metadata and keywords
- calendar events
- notification-derived payment/reservation/transport events
- app usage summaries
- local Markdown/PDF/OCR-derived indexes

The user can ask questions such as:

- Where did I go yesterday?
- What did I do yesterday?
- Was I drinking last week?
- Am I busy next week?
- Find food photos from last month.

Every answer must include evidence, inference, confidence, and missing-data explanation.

## Zero Raw Retention

Grayin AI never stores original user data.

Original data is accessed only transiently in memory, processed immediately, and discarded.

The app stores only source references and derived memory/index data.

Core model types are restricted to source references, derived memory events, evidence, citations, confidence, and missing-data explanations. They do not define fields for raw/original content.

## Android App

The app is a Kotlin Android project using Jetpack Compose.

Current package structure:

- `ai.grayin.app`
- `ai.grayin.core.model`
- `ai.grayin.core.connector`
- `ai.grayin.core.store`
- `ai.grayin.core.indexing`
- `ai.grayin.core.retrieval`
- `ai.grayin.core.grounding`
- `ai.grayin.core.security`
- `ai.grayin.core.ai`
- `ai.grayin.connectors.location`
- `ai.grayin.connectors.photos`
- `ai.grayin.connectors.calendar`
- `ai.grayin.connectors.notification`
- `ai.grayin.connectors.usagestats`
- `ai.grayin.connectors.localfiles`

Build command:

```bash
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:assembleDebug
```

Test command:

```bash
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:testDebugUnitTest
```

The manifest must not include `android.permission.INTERNET`.

## Known Remaining Work

- real location, photos, calendar, notification, and app-usage connectors
- PDF/OCR local-file indexing
- real on-device Gemma adapter
- encrypted export/import runtime
- optional screenshot blocking and biometric lock
