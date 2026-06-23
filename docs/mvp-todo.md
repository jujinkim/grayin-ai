# Grayin AI MVP TODO

Before implementing any task, read:

- `docs/product-spec.md`
- `docs/zero-raw-retention.md`
- `docs/non-agentic-boundary.md`
- `docs/privacy-model.md`
- `docs/threat-model.md`
- `docs/connector-policy.md`

## Workflow

1. Pick the next incomplete task.
2. Implement only that coherent step.
3. Update this TODO file.
4. Update affected docs.
5. Run available checks/builds.
6. Commit changes.

## Hard Constraints

- [x] INTERNET permission restricted to typed online enrichment methods.
- [x] Never store raw/original data.
- [x] Never log raw/original data.
- [x] Never cache raw/original data.
- [x] Never export raw/original data.
- [x] Never sync or transmit raw/original data.
- [x] Store only SourceReference and derived memory/index data.
- [x] No agentic action APIs.
- [x] No ads, analytics, crash SDK, account, server, or cloud.

---

## MVP 0 — Documentation and Architecture

- [x] Create `README.md`.
- [x] Create `docs/product-principles.md`.
- [x] Create `docs/zero-raw-retention.md`.
- [x] Create `docs/non-agentic-boundary.md`.
- [x] Create `docs/privacy-model.md`.
- [x] Create `docs/threat-model.md`.
- [x] Create `docs/connector-policy.md`.
- [x] Create `docs/mvp-scope.md`.
- [x] Create `docs/mvp-todo.md`.
- [x] Explicitly state no raw/original data storage.
- [x] Explicitly state no action APIs.
- [x] Explicitly state typed online enrichment network boundary.
- [x] Commit documentation phase.

## MVP 1 — Android Project Skeleton

- [x] Create native Android project using Kotlin.
- [x] Use Jetpack Compose for UI.
- [x] Create modular package structure:
  - [x] `app`
  - [x] `core.model`
  - [x] `core.connector`
  - [x] `core.store`
  - [x] `core.indexing`
  - [x] `core.retrieval`
  - [x] `core.grounding`
  - [x] `core.security`
  - [x] `core.ai`
  - [x] `connectors.location`
  - [x] `connectors.photos`
  - [x] `connectors.calendar`
  - [x] `connectors.notification`
  - [x] `connectors.usagestats`
  - [x] `connectors.localfiles`
- [x] Add placeholder screens:
  - [x] Ask
  - [x] Timeline
  - [x] Places
  - [x] Sources
  - [x] Settings
- [x] Ensure home screen is Ask-first.
- [x] Ensure manifest includes INTERNET permission only for typed enrichment.
- [x] Commit project skeleton.

## MVP 2 — Core Data Model

- [x] Add `SourceReference`.
- [x] Add `DerivedMemoryEvent`.
- [x] Add `MemoryCitation`.
- [x] Add `EvidenceItem`.
- [x] Add `EvidencePack`.
- [x] Add `GroundedAnswer`.
- [x] Add `InferenceStep`.
- [x] Add `MissingSource`.
- [x] Add `ConnectorState`.
- [x] Add `ConnectorCapability`.
- [x] Add `MemoryCapability`.
- [x] Add `SensitivityLevel`.
- [x] Add `ConfidenceLevel`.
- [x] Add `ProcessingState`.
- [x] Add `SourceAvailability`.
- [x] Add `DailyMemorySummary`.
- [x] Add `PlaceCluster`.
- [x] Add `PlaceVisit`.
- [x] Add `PhotoMemoryIndex`.
- [x] Add `NotificationDerivedEvent`.
- [x] Add `AppUsageSummary`.
- [x] Ensure no model requires raw content persistence.
- [x] Commit core model phase.

## MVP 3 — Connector Abstraction

- [x] Create connector interface.
- [x] Create connector metadata model.
- [x] Create connector permission state model.
- [x] Create connector revoke/delete contract.
- [x] Ensure connector emits only derived events.
- [x] Ensure raw content cannot be passed into store APIs.
- [x] Commit connector abstraction.

## MVP 4 — Local Store

- [x] Create local store abstraction.
- [x] Support saving source references.
- [x] Support saving derived memory events.
- [x] Support saving citations.
- [x] Support saving daily summaries.
- [x] Support saving place clusters.
- [x] Support saving app usage summaries.
- [x] Support connector-level delete.
- [x] Support index invalidation after delete.
- [x] Add SQLCipher TODO.
- [x] Add Android Keystore TODO.
- [x] Commit local store abstraction.

## MVP 5 — Indexing Queue

- [x] Create processing queue model.
- [x] Add pending/running/completed/failed/skipped/stale states.
- [x] Add automatic indexing policy: charging + low-usage/night + battery + thermal.
- [x] Add manual indexing commands: index now, connector, date range.
- [x] Add indexing status model for UI.
- [x] Commit indexing queue.

## MVP 6 — Initial Connectors as Stubs

- [x] Add Location Connector stub.
- [x] Add Photos Connector stub.
- [x] Add Calendar Connector stub.
- [x] Add Notification Connector stub.
- [x] Add App Usage Connector stub.
- [x] Add Local Files Connector stub.
- [x] Add connector sensitivity defaults.
- [x] Add TODOs for real platform permission implementation.
- [x] Commit stub connectors.

## MVP 7 — Retrieval and Query Planning

- [x] Create query intent model.
- [x] Parse approximate time expressions.
- [x] Support location recall intent.
- [x] Support schedule recall intent.
- [x] Support future busyness check intent.
- [x] Support photo recall intent.
- [x] Support notification/payment recall intent.
- [x] Support app usage recall intent.
- [x] Support general memory recall intent.
- [x] Support night-out route reconstruction intent.
- [x] Determine required/optional/available/missing capabilities.
- [x] Commit retrieval/query planner.

## MVP 8 — Grounded Answer Format

- [x] Implement template-based grounded answer generator.
- [x] Include answer.
- [x] Include evidence.
- [x] Include inference.
- [x] Include confidence.
- [x] Include missing data.
- [x] Include source links or references.
- [x] Enforce no uncited claims.
- [x] Commit grounded answer format.

## MVP 9 — Local AI Adapter

- [x] Create `LocalLanguageModel` interface.
- [x] Add replaceable Gemma 4 adapter placeholder.
- [x] Add fake/stub implementation.
- [x] Ensure model receives only Evidence Pack.
- [x] Ensure no commercial LLM API.
- [x] Ensure no network access.
- [x] Commit local AI adapter.

## MVP 10 — UI

- [x] Add Ask screen.
- [x] Add answer card.
- [x] Add expandable evidence section.
- [x] Add missing-data section.
- [x] Add confidence label.
- [x] Add Sources screen.
- [x] Add Timeline screen.
- [x] Add Places screen.
- [x] Add Settings screen.
- [x] Add manual indexing button placeholder.
- [x] Add connector revoke/delete placeholder.
- [x] Commit UI skeleton.

## MVP 11 — Security and Backup Policy

- [x] Document SQLCipher plan.
- [x] Document Android Keystore plan.
- [x] Document encrypted export/import plan.
- [x] Configure or document backup exclusion.
- [x] Ensure no crash analytics.
- [x] Ensure no ad SDK.
- [x] Ensure no telemetry.
- [x] Ensure no raw logs.
- [x] Add optional screenshot blocking TODO.
- [x] Add optional biometric app lock TODO.
- [x] Commit security policy.

## MVP 12 — Export/Import Design

- [x] Document encrypted export format.
- [x] Document export allowed data.
- [x] Document export forbidden data.
- [x] Document import re-consent requirement.
- [x] Add skeleton if simple.
- [x] Commit export/import design.

## MVP 13 — Benchmark Questions

- [x] Create `benchmarks/query-set.md`.
- [x] Add question: Where did I go yesterday?
- [x] Add question: What did I do yesterday?
- [x] Add question: Was I drinking last week?
- [x] Add question: Did I call my family this week?
- [x] Add question: What meetings did I have yesterday?
- [x] Add question: Am I busy next week?
- [x] Add question: Find food photos from last month.
- [x] Add question: Around this time last year, did I go drinking in Seoul, and what was the route?
- [x] Define required capabilities per question.
- [x] Define optional capabilities per question.
- [x] Define expected evidence types.
- [x] Define missing-data behavior.
- [x] Define confidence rules.
- [x] Commit benchmarks.

---

## Usable Local MVP — Text/Markdown Recall

- [x] Add SQLCipher-backed local memory store.
- [x] Protect generated SQLCipher passphrase with Android Keystore.
- [x] Implement real local `.txt` / `.md` connector.
- [x] Wire Ask flow to indexed local evidence.
- [x] Add local-file selection and indexing UI.
- [x] Add verification tests.
- [x] Update user-facing and engineering docs.
- [x] Push completed work.

## Online Enrichment Policy

- [x] Allow `android.permission.INTERNET`.
- [x] Add typed `OnlineEnrichmentGateway`.
- [x] Restrict online features to weather lookup and reverse geocode.
- [x] Reject arbitrary URL or endpoint method surfaces.
- [x] Push online-enrichment policy update.
