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

- [x] No INTERNET permission in MVP.
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
- [x] Explicitly state no INTERNET permission in MVP.
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
- [x] Ensure manifest does not include INTERNET permission.
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

- [ ] Create local store abstraction.
- [ ] Support saving source references.
- [ ] Support saving derived memory events.
- [ ] Support saving citations.
- [ ] Support saving daily summaries.
- [ ] Support saving place clusters.
- [ ] Support saving app usage summaries.
- [ ] Support connector-level delete.
- [ ] Support index invalidation after delete.
- [ ] Add SQLCipher TODO.
- [ ] Add Android Keystore TODO.
- [ ] Commit local store abstraction.

## MVP 5 — Indexing Queue

- [ ] Create processing queue model.
- [ ] Add pending/running/completed/failed/skipped/stale states.
- [ ] Add automatic indexing policy: charging + low-usage/night + battery + thermal.
- [ ] Add manual indexing commands: index now, connector, date range.
- [ ] Add indexing status model for UI.
- [ ] Commit indexing queue.

## MVP 6 — Initial Connectors as Stubs

- [ ] Add Location Connector stub.
- [ ] Add Photos Connector stub.
- [ ] Add Calendar Connector stub.
- [ ] Add Notification Connector stub.
- [ ] Add App Usage Connector stub.
- [ ] Add Local Files Connector stub.
- [ ] Add connector sensitivity defaults.
- [ ] Add TODOs for real platform permission implementation.
- [ ] Commit stub connectors.

## MVP 7 — Retrieval and Query Planning

- [ ] Create query intent model.
- [ ] Parse approximate time expressions.
- [ ] Support location recall intent.
- [ ] Support schedule recall intent.
- [ ] Support future busyness check intent.
- [ ] Support photo recall intent.
- [ ] Support notification/payment recall intent.
- [ ] Support app usage recall intent.
- [ ] Support general memory recall intent.
- [ ] Support night-out route reconstruction intent.
- [ ] Determine required/optional/available/missing capabilities.
- [ ] Commit retrieval/query planner.

## MVP 8 — Grounded Answer Format

- [ ] Implement template-based grounded answer generator.
- [ ] Include answer.
- [ ] Include evidence.
- [ ] Include inference.
- [ ] Include confidence.
- [ ] Include missing data.
- [ ] Include source links or references.
- [ ] Enforce no uncited claims.
- [ ] Commit grounded answer format.

## MVP 9 — Local AI Adapter

- [ ] Create `LocalLanguageModel` interface.
- [ ] Add replaceable Gemma 4 adapter placeholder.
- [ ] Add fake/stub implementation.
- [ ] Ensure model receives only Evidence Pack.
- [ ] Ensure no commercial LLM API.
- [ ] Ensure no network access.
- [ ] Commit local AI adapter.

## MVP 10 — UI

- [ ] Add Ask screen.
- [ ] Add answer card.
- [ ] Add expandable evidence section.
- [ ] Add missing-data section.
- [ ] Add confidence label.
- [ ] Add Sources screen.
- [ ] Add Timeline screen.
- [ ] Add Places screen.
- [ ] Add Settings screen.
- [ ] Add manual indexing button placeholder.
- [ ] Add connector revoke/delete placeholder.
- [ ] Commit UI skeleton.

## MVP 11 — Security and Backup Policy

- [ ] Document SQLCipher plan.
- [ ] Document Android Keystore plan.
- [ ] Document encrypted export/import plan.
- [ ] Configure or document backup exclusion.
- [ ] Ensure no crash analytics.
- [ ] Ensure no ad SDK.
- [ ] Ensure no telemetry.
- [ ] Ensure no raw logs.
- [ ] Add optional screenshot blocking TODO.
- [ ] Add optional biometric app lock TODO.
- [ ] Commit security policy.

## MVP 12 — Export/Import Design

- [ ] Document encrypted export format.
- [ ] Document export allowed data.
- [ ] Document export forbidden data.
- [ ] Document import re-consent requirement.
- [ ] Add skeleton if simple.
- [ ] Commit export/import design.

## MVP 13 — Benchmark Questions

- [ ] Create `benchmarks/query-set.md`.
- [ ] Add question: Where did I go yesterday?
- [ ] Add question: What did I do yesterday?
- [ ] Add question: Was I drinking last week?
- [ ] Add question: Did I call my family this week?
- [ ] Add question: What meetings did I have yesterday?
- [ ] Add question: Am I busy next week?
- [ ] Add question: Find food photos from last month.
- [ ] Add question: Around this time last year, did I go drinking in Seoul, and what was the route?
- [ ] Define required capabilities per question.
- [ ] Define optional capabilities per question.
- [ ] Define expected evidence types.
- [ ] Define missing-data behavior.
- [ ] Define confidence rules.
- [ ] Commit benchmarks.
