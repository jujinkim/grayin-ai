# Grayin AI Product Spec

Grayin AI is an Android-only, local-first personal memory indexer and search engine.

It helps the user recall their life by indexing user-enabled local phone data into derived memory events, then answering natural-language questions with evidence, confidence, and missing-data explanations.

## Meaning of the Name

**Grayin AI** means **Gray In AI**, a twist on the “gray out” phenomenon where memory becomes vague, interrupted, or blurred.

Grayin AI helps recover faded memory from local evidence.

## Tagline

> Your data stays where it is. Your memory becomes searchable.

## Philosophy

AI is becoming our arms and legs through agentic systems.

Grayin AI is different.

It does not act on behalf of the user. It helps humans see, remember, and decide better.

Grayin AI is not an AI assistant that does things for you. It is a memory engine that helps you remember enough to act for yourself.

## What Grayin AI Is

Grayin AI is:

- an Android-native memory indexer
- a local-first personal context engine
- a phone-data search engine
- an evidence-grounded recall interface
- a non-agentic personal AI system
- a zero-raw-retention memory layer

## What Grayin AI Is Not

Grayin AI is not:

- a cloud app
- a generic notes app
- an agentic AI product
- a personal automation agent
- a raw data storage vault
- a spyware-like background collector
- an app that silently uploads data
- an app that acts on behalf of the user

## Core Principles

- No Grayin application backend.
- No account.
- No cloud storage or sync.
- Network access is allowed only for typed external enrichment and fixed-catalog model/manifest downloads.
- No commercial LLM API in MVP.
- No raw/original data storage, ever.
- No raw/original data transmission, ever.
- No raw/original data logging, ever.
- No raw/original data caching, ever.
- No raw/original data export, ever.
- No agentic action APIs.
- Android-native only for MVP.
- Local-first by default.
- External enrichment must be explicit and limited to minimal derived lookup inputs for map, place, reverse-geocode, or weather requests.
- App feature code, connectors, local models, and users must not call or provide arbitrary URLs or endpoints.
- Runtime model and manifest downloads must use fixed catalog URLs with pinned SHA-256 digests; remote manifests require a pinned ECDSA P-256 signature. APK/AAB must not bundle model weights.
- Raw sources, stored derived-memory records, evidence packs, prompts, answers, embeddings, source references, and fields outside an approved ephemeral enrichment-request projection must never be transmitted.
- All data sources are explicit opt-in.
- Every connector can be enabled, disabled, revoked, and deleted independently.
- The app only reasons over locally available, user-enabled, indexed evidence.
- If data is unavailable, denied, unsupported, or not indexed, the answer must say so.
- Answers must be evidence-grounded with confidence and missing-data explanation.

## Product Mental Model

The user does not want to browse raw data manually.

The user wants to ask:

- “Where did I go yesterday?”
- “What did I do yesterday?”
- “What was yesterday’s meeting about?”
- “Did I call my family this week?”
- “Was I busy last week?”
- “When did I go drinking last week?”
- “Last month, what was the pretty food I photographed on a date?”
- “Around this time last year, I drank in Seoul. What were the 1st, 2nd, and 3rd places, and when did I move between them?”

Grayin AI should answer these questions using indexed local evidence.

## Supported Evidence Types in MVP

The MVP should conceptually support these evidence sources through implemented connectors or future indexing paths:

- location clusters
- place visits
- calendar events
- photo metadata
- photo keywords
- photo clusters
- notification-derived payment events
- notification-derived delivery/reservation/transport events
- app usage summaries
- local files
- Markdown notes
- PDF/page indexes
- OCR-derived text
- future local LLM-generated summaries

Current usable local MVP implementation supports user-selected `.txt` and `.md` files, connected Android last-known location samples, connected Android calendar events, connected Android photo metadata, connected Android app usage summaries, and connected Android notification-derived signals after explicit permission or settings access. A separate default-OFF Location switch permits bounded Android reverse geocoding and fixed Open-Meteo weather lookup through `OnlineEnrichmentGateway`; failures preserve coordinate-only local indexing. Ask can use a local Gemma LiteRT-LM model over retrieved `EvidencePack` data when a runtime-downloaded or manually imported `.litertlm` model file is installed, with template fallback when unavailable. Unsupported evidence types remain future work until their platform permissions and zero-raw-retention processing paths are implemented.

## Important Definitions

### Raw Original Data

Original source data, such as:

- original photo file
- notification text
- message text
- PDF file
- local file content
- calendar raw record
- usage event dump
- browser raw log
- audio/video content
- original note file

Raw original data must never be stored, logged, cached, exported, synced, or transmitted by Grayin AI.

### SourceReference

A local pointer/reference to original data.

Examples:

- content URI
- file URI
- package name
- external ID
- timestamp
- HMAC hash
- modified time
- source app identifier

### DerivedMemoryEvent

A structured memory event generated from a source.

It may contain:

- minimal extracted fields
- summaries
- keywords
- labels
- entities
- embeddings
- confidence
- citations
- source reference IDs

### Zero Raw Retention

Raw original data is only accessed transiently in memory, processed immediately, and discarded.

It must never be persisted, logged, cached, synced, exported, backed up, or transmitted.

### Evidence Pack

A set of retrieved indexed evidence items passed to a local LLM or template answer generator.

The Evidence Pack must contain only derived/indexed evidence, never raw original data.

### Grounded Answer

An answer that includes:

- answer
- evidence
- inference explanation
- confidence
- missing data
- source links or source references

## Architecture Principle

Connector reads original source transiently.

Core never receives raw original data.

Store cannot persist raw original data.

Indexer stores only source references and derived memory.

Retriever searches only indexed local derived evidence.

LLM receives only Evidence Pack, never raw original data.

## Core Pipeline

```text
Source Connector
→ SourceReference
→ transient in-memory processing
→ DerivedMemoryEvent
→ Indexes
→ Retrieval
→ Evidence Pack
→ Local LLM or Template Answer
→ Grounded Answer
```

## MVP Connectors

### Location Connector

Purpose:

- produce place visits
- produce place clusters
- reconstruct daily movement
- support questions like “Where did I go yesterday?”

Rules:

- no bundled offline map data in MVP
- external map/place providers may be used only through typed `OnlineEnrichmentGateway` methods
- weather lookups may use a fixed external provider through the typed enrichment gateway
- reverse-geocode lookup may use Android's geocoder or a fixed provider through the typed enrichment gateway
- only rounded coordinates, timestamps, locale, units, or an explicitly approved coarse place query may leave the device
- source connection does not imply online enrichment consent; the enrichment switch defaults OFF and revocation disables it
- current reverse geocoding transmits only a 0.001-degree coordinate; current weather transmits only a 0.01-degree coordinate and UTC date
- the local LLM must not call map APIs directly; map/place enrichment must remain outside the model path
- exact business names are not required
- focus on region, movement, stay duration, and user-defined place labels

### Photos Connector

Purpose:

- reference photos through URI/source reference
- extract metadata where possible
- create photo memory index records
- create placeholder keywords in MVP

Rules:

- never copy photo files
- never store original images
- never pass original images to store
- create derived photo index only

### Calendar Connector

Purpose:

- index calendar events where permission allows
- create meeting/schedule/busy-time derived events
- support future busyness questions

### Notification Connector

Purpose:

- derive structured life events from notifications
- payment, delivery, reservation, transport, message hint, security hint

Rules:

- highly sensitive connector
- default OFF
- app allowlist required
- raw notification text must never be saved
- raw notification text must never be logged
- raw notification text must never be cached
- OTP/security codes must not be stored
- process notification text transiently and discard immediately

### App Usage Connector

Purpose:

- index usage stats where permission allows
- derive app usage summaries
- infer context such as work/study/entertainment patterns

Rules:

- do not store raw usage event dumps
- store only summaries, buckets, aliases, and derived usage events

### Local Files Connector

Purpose:

- reference user-selected folders/files
- support Markdown, PDF, image exports, and handwriting-note exports conceptually
- support page-level indexing for PDF/handwriting exports

Rules:

- never copy original files
- never store original file content
- index only derived text, summary, keywords, embeddings, citations

## Indexing Policy

Automatic indexing should run only when:

- device is charging when the user keeps the charging-only guard enabled
- it is a user-configured low-usage time window such as night
- battery is sufficient
- thermal state is acceptable

Manual indexing should support:

- index now
- index one connector
- index one date range

Processing states:

- pending
- running
- completed
- failed
- skipped
- stale

## Retrieval and Answering

The app must use an Ask-first interface.

Home is not a note list. Home is a memory question input.

On first installed launch, the app may open Sources first to explain that sources must be explicitly connected and indexed before Grayin can read, analyze, and answer from them. After that intro is marked seen, normal launches return to the Ask-first interface.

The MVP UI supports English, Korean, and Japanese copy. Settings exposes language choices as `system`, `korean`, `english`, and `japanese`; `system` resolves from the Android system language and falls back to English for unsupported languages.

Primary navigation uses a bottom navigation bar with icons and localized labels for Ask, Timeline, Places, Sources, and Settings.

The retrieval pipeline:

```text
User Query
→ Query Planner
→ Capability Resolution
→ Indexed Retrieval
→ Evidence Pack
→ Template or Local LLM Answer
→ Grounded Answer
```

Every answer must include:

- answer
- evidence
- inference
- confidence
- missing data
- source links or references

Rules:

- No uncited claims.
- No hidden inference.
- No pretending unavailable data exists.
- No unsupported certainty.

## Local AI Strategy

MVP target model: Gemma 4 on-device through LiteRT-LM.

However, the model must be replaceable.

The app should use `Gemma4LocalLanguageModel` when a local `.litertlm` model file is installed, and fall back to deterministic grounded templates when it is not ready.

The APK must not bundle Gemma model weights. Settings should tell users that model weights come from the official Google AI Edge LiteRT-LM Gemma docs or Hugging Face repo `litert-community/gemma-4-E2B-it-litert-lm`, then let users import a local `.litertlm` file into app-private storage. Developer installation under `/data/local/tmp/grayin/` remains supported.

No remote or commercial LLM API in MVP.

Local inference must not require network access.

Model acquisition may use fixed-catalog HTTPS downloads before inference. Map/place, weather, and reverse-geocode enrichment may use the network outside the local AI path through typed gateway methods only.

The local LLM receives only Evidence Pack.

The local LLM must never receive raw original data.

## Non-Agentic Boundary

Grayin AI must not implement action APIs.

Forbidden APIs include:

- sendEmail
- sendMessage
- makeCall
- createCalendarEvent
- deleteExternalFile
- uploadData
- syncToServer
- postToSocial
- purchase
- reserve
- book
- autoReply

Allowed APIs include:

- recall
- searchMemory
- summarizeEvidence
- explainReasoning
- showSource
- getTimeline
- getPlaceHistory
- getTopicHistory

## Security and Backup

MVP should document and prepare for:

- SQLCipher
- Android Keystore
- encrypted export/import
- backup exclusion
- no crash analytics
- no ad SDK
- no telemetry
- no raw logs
- optional screenshot blocking
- optional biometric app lock

MVP uses INTERNET permission for typed external enrichment and fixed-catalog model/manifest downloads.

It must not use network access for arbitrary or user-supplied URL calls, cloud sync, accounts, telemetry, raw data upload, stored derived-memory upload, fields outside an approved ephemeral enrichment-request projection, or remote LLM APIs. `docs/network-policy.md` is the canonical network boundary.

Prefer `allowBackup=false` unless there is a documented reason otherwise.

## Export and Import

MVP should document the design.

Encrypted export may include:

- source references
- derived memory events
- summaries
- indexes or rebuildable index data
- entity graph
- place clusters
- connector metadata

Encrypted export must not include:

- photo originals
- PDF originals
- notification originals
- raw messages
- raw usage logs
- raw calendar dumps
- raw files

Import should require re-consent for connectors on the new device.

## Development Workflow

After each coherent step:

1. update affected documentation
2. update `docs/roadmap.md` when scope or future work changes
3. run available checks/builds
4. commit the changes with a clear git commit message

If git is unavailable, initialize it unless clearly inappropriate.
