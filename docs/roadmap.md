# Roadmap

Current phase status: local-first MVP foundation and basic Android source connectors are implemented. Completion work below tracks production correctness, remaining features, and hardening.

## Current Capability

- User-selected `.txt` and `.md` files through Android document picker.
- User-connected Android last-known location samples through runtime location permission.
- User-connected Android calendar events through runtime calendar read permission.
- User-connected Android photo metadata through runtime media read permission.
- User-connected Android app usage summaries through usage-access settings.
- User-connected Android notification-derived signals through notification-listener settings access.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- Local Gemma LiteRT-LM answer adapter over retrieved `EvidencePack`, with template fallback when the model file is unavailable.
- Runtime local-model catalog for Grayin dedicated model placeholder, official Gemma 4 E2B, and official Gemma 4 E4B.
- WorkManager runtime model download into app-private storage; APK/AAB does not bundle model weights.
- App-specific model training scaffold under `model-training/`, with Gemma/reference/output model artifacts excluded from git.
- First-launch Sources intro explaining that user-connected sources must be indexed before Ask can use them.
- Sources UI is backed by connector metadata and permission/index state.
- Sources UI exposes top-level Index all now and persisted automatic indexing settings.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- Settings shows local model selection, download/cancel/delete controls, official model pages, local Gemma status, current adb install path, and `.litertlm` import/delete fallback controls.
- INTERNET permission bounded by `docs/network-policy.md`: typed map/place/reverse-geocode/weather enrichment plus fixed-catalog model/manifest downloads.

## Completion Plan

Each step updates affected docs, runs available checks, and produces one coherent commit.

### 0. Canonical Network Policy

- [x] Define allowed typed external enrichment and fixed-catalog artifact-download boundaries.
- [x] Forbid arbitrary/user-supplied endpoints, raw or derived memory transmission, remote LLMs, application backends, accounts, sync, and telemetry.
- [x] Align repository instructions and product/privacy/security/network docs.

### 1. Retrieval and Connector Correctness

- [ ] Resolve available capabilities from every indexed connector instead of Local Files only.
- [ ] Preserve per-event capabilities through retrieval and missing-data calculation.
- [ ] Validate local-model evidence IDs and citations before displaying a generated answer.
- [ ] Add and enforce the notification application allowlist required by the product spec.

### 2. Typed External Enrichment

- [ ] Implement a fixed weather provider behind `OnlineEnrichmentGateway`.
- [ ] Keep map/place and reverse-geocode operations inside the typed gateway.
- [ ] Add timeout, schema validation, explicit unavailable results, and network-boundary tests.

### 3. Automatic Indexing Runtime

- [ ] Add a persistent indexing queue implementation and command executor.
- [ ] Add WorkManager scheduling for enabled automatic indexing.
- [ ] Enforce charging, low-usage window, battery, and thermal conditions at runtime.
- [ ] Surface last run, queued work, skipped reason, and failure state in Sources.

### 4. PDF and OCR Indexing

- [ ] Accept user-selected PDF documents.
- [ ] Extract text and page metadata transiently; render and OCR pages locally when embedded text is unavailable.
- [ ] Persist only derived page summaries, keyword signals, citations, and source references.
- [ ] Add size/page/time limits and explicit unsupported/missing-data results.

### 5. Encrypted Export and Import

- [ ] Implement password-protected authenticated export containing allowed derived sections only.
- [ ] Implement schema/version/integrity validation and transactional import.
- [ ] Require connector re-consent after import.
- [ ] Add Android document create/open flows without cloud sync or automatic backup.

### 6. Optional App Security

- [ ] Add persisted screenshot-blocking preference and apply `FLAG_SECURE` when enabled.
- [ ] Add biometric/device-credential app lock with explicit fallback and recovery behavior.
- [ ] Add lifecycle tests for lock and unlock transitions.

### 7. Source and UI Completion

- [ ] Build place history/cluster output from indexed location evidence and populate Places.
- [ ] Refresh usage/notification permission state after returning from Android settings.
- [ ] Localize connector status, error, and derived-summary presentation.
- [ ] Complete date-range indexing and visible indexing status.

### 8. Local Model Release Hardening

- [ ] Require pinned SHA-256 for every downloaded model; verify remote manifests with a bundled ECDSA P-256 public key.
- [ ] Validate imported model identity beyond extension and minimum size.
- [ ] Expand synthetic training/evaluation data and run grounded-answer benchmarks.
- [ ] Merge/export the release adapter to `.litertlm` outside git.
- [ ] Publish the Grayin model to an immutable external artifact URL and configure catalog metadata.

External artifact publication requires a selected host, release credentials, license/terms URL, and final model file. Repository work must prepare and validate the release without committing weights or credentials.

### 9. Final Verification

- [ ] Add JVM coverage for policy, queue, crypto, parsing, capability, and grounding behavior.
- [ ] Add Android instrumentation tests for permissions, SQLCipher, document flows, WorkManager, notification filtering, and app security.
- [ ] Run unit tests, Android lint, debug/release builds, security scans, and device smoke tests.
- [ ] Remove stale roadmap/status text and confirm all hard constraints.
