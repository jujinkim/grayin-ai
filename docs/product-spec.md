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

- No server.
- No account.
- No cloud in MVP.
- Internet permission is allowed only through typed online enrichment methods in MVP.
- No commercial LLM API in MVP.
- No raw/original data storage, ever.
- No raw/original data transmission, ever.
- No raw/original data logging, ever.
- No raw/original data caching, ever.
- No raw/original data export, ever.
- No agentic action APIs.
- Android-native only for MVP.
- Local-first by default.
- Online enrichment must be explicit and limited to derived lookup inputs, such as weather or reverse-geocode requests.
- App feature code must not call arbitrary URLs or endpoints.
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

The MVP should conceptually support these evidence sources through connector stubs or partial implementations:

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

Current usable local MVP implementation supports user-selected `.txt` and `.md` files, invoked Android last-known location samples, invoked Android calendar events, invoked Android photo metadata, and invoked Android app usage summaries after explicit permission or settings access. Other evidence sources remain connector stubs until their platform permissions and zero-raw-retention processing paths are implemented.

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

- no offline map data in MVP
- no online map SDK in MVP
- weather and reverse-geocode lookups may use network permission through typed enrichment methods when implemented
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

- device is charging
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

On first installed launch, the app may open Sources first to explain that sources must be explicitly invoked and indexed before Grayin can read, analyze, and answer from them. After that intro is marked seen, normal launches return to the Ask-first interface.

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

MVP target model: Gemma 4 on-device.

However, the model must be replaceable.

MVP should start with a stub LocalLanguageModel implementation.

No commercial LLM API in MVP.

Local AI must not require network access.

Weather and reverse-geocode enrichment may use app network permission outside the local AI path through typed enrichment methods only.

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

MVP may request INTERNET permission for typed online enrichment.

It must not use network access for arbitrary URL calls, cloud sync, accounts, telemetry, raw source upload, or commercial LLM APIs.

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
