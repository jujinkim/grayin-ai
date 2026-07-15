# Grayin AI

Grayin AI is a local-first Android memory indexer that helps you recall your life without storing your original data.

> Your data stays where it is. Your memory becomes searchable.

## Meaning of the Name

**Grayin AI** means **Gray In AI**: AI that helps bring back memories that have gone gray, vague, or blurred.

## Philosophy

AI is becoming our arms and legs. Grayin AI is different: it helps humans see, remember, and decide better.

Grayin AI is non-agentic. It does not act on behalf of the user. It helps the user recall local, indexed evidence.

## Principles

- No application backend
- No account
- No cloud storage or sync
- Network access only for typed external enrichment and fixed-catalog model downloads
- No raw data retention
- No commercial LLM API in MVP
- No agentic actions
- User-enabled sources only
- Local indexed evidence only
- Open source

## What It Does

Grayin AI indexes user-enabled local Android data sources into derived memory events.

Current usable app path:

- user connects Local Files, Location, Photos, Calendar, Notifications, or App Usage explicitly
- connectors read source data transiently during indexing or approved listener callbacks
- app stores only source references, keyword signals, summaries, citations, and confidence
- SQLCipher encrypts the local derived-memory store
- Android Keystore protects the generated SQLCipher passphrase
- Ask answers from indexed, cited local evidence and lists missing sources
- Settings supports language selection: `system`, `korean`, `english`, or `japanese`
- Bottom navigation uses icons and localized labels

## Network Boundary

Grayin AI is local-first, not offline-only.

Network use is limited to:

- typed map, place, reverse-geocode, and weather enrichment using minimal derived lookup inputs
- fixed-catalog model and authenticated-manifest downloads into app-private storage

The app does not expose arbitrary or user-entered endpoints. External enrichment receives only an ephemeral typed lookup projection, never a stored derived-memory record. The app does not upload raw sources, evidence packs, prompts, answers, embeddings, or source references. It does not use remote LLMs, application backends, accounts, cloud sync, telemetry, ads, or crash reporting. See `docs/network-policy.md`.

External enrichment may fetch map/place, weather, or reverse-geocode details through typed gateway methods. Runtime model downloads use fixed catalog URLs. Both paths follow `docs/network-policy.md`.

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

The manifest includes `android.permission.INTERNET` for typed external enrichment and fixed-catalog model downloads.

## Roadmap

Current phase is complete. Future work is tracked in `docs/roadmap.md`.
