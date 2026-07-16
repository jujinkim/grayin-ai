# Roadmap

Current phase status: the repository-local MVP implementation plan is complete through Step 9, and the authorized long-term memory-engine expansion is active. `docs/remaining-work.md` is the single source of truth for external release inputs, physical-device acceptance, release quality, and expansion work.

## Current Capability

- User-selected Text, Markdown, and PDF documents through Android document picker, with page-level PDF indexing and local OCR fallback.
- User-connected Android last-known location samples through runtime location permission.
- User-connected Android calendar events through runtime calendar read permission.
- User-connected Android photo metadata through runtime media read permission.
- User-connected Android completed app-usage session events through usage-access settings.
- User-connected Android notification-derived signals through notification-listener settings access.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- Local Gemma LiteRT-LM answer adapter over retrieved `EvidencePack`, with template fallback when the model file is unavailable.
- Runtime local-model catalog with a dedicated Grayin WI8/AFP32 release identity plus official Gemma 4 E2B/E4B information-page entries.
- Fail-closed signed-manifest and WorkManager model-download infrastructure using the shared fixed-artifact verifier; production trust configuration and model transport remain disabled until a reviewed external release exists.
- App-specific model training, LoRA merge, explicit WI8/AFP32 LiteRT export, exact 30-fixture CLI evaluation, and local provenance pipeline under `model-training/`, with all model artifacts excluded from git.
- First-launch Sources intro explaining that user-connected sources must be indexed before Ask can use them.
- Sources UI is backed by connector metadata and permission/index state.
- Sources UI exposes top-level Index all now, persisted automatic indexing settings, and live queue/runtime status with localized outcomes.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- Settings shows local model selection, official model pages, local Gemma status, and `.litertlm` import/delete fallback controls. External-files/ADB development paths exist only in debuggable builds and are absent from release resolution and guidance. Catalog download/cancel/delete actions render only after a model entry has complete reviewed transport metadata; none currently does.
- Settings installs, cancels, and deletes fixed-catalog English, Korean, and Japanese OCR language data only after an explicit user action; document indexing never initiates a download.
- Local Files passes explicitly selected PDF descriptors to a private `:document` Pdfium/Tesseract runtime, which enforces descriptor, signature, page, bitmap, text, OCR, and time limits and returns only bounded derived AIDL results.
- Local document selection stores only Keystore HMAC markers. SQLCipher schema v8 retains the v6 Local Files identity purge and additionally fences work and removes legacy open-schema Location, Calendar, Photos, Notifications, and App Usage records.
- Settings provides explicit encrypted export/import of the validated seven-section wire snapshot with no Grayin-owned network transport. Schema v8 retains the reserved daily-summary and app-usage-summary arrays but requires both to be empty. Version 1 uses password-derived AES-256-GCM, exact current-schema validation, replace-only SQLCipher import, and mandatory connector re-consent; the Android picker requests on-device documents but an external provider controls its own storage behavior.
- Settings provides independent persisted screenshot blocking and system biometric/device-credential app lock. App lock forces `FLAG_SECURE`, starts locked in a new process, and relocks after a non-configuration background transition.
- INTERNET permission bounded by `docs/network-policy.md`: typed map/place/reverse-geocode/weather enrichment plus fixed-catalog model, authenticated manifest, or OCR language-data downloads.

## Completion Plan

Each step updates affected docs, runs available checks, and produces one coherent commit.

### 0. Canonical Network Policy

- [x] Define allowed typed external enrichment and fixed-catalog artifact-download boundaries.
- [x] Forbid arbitrary/user-supplied endpoints, raw or derived memory transmission, remote LLMs, application backends, accounts, sync, and telemetry.
- [x] Align repository instructions and product/privacy/security/network docs.

### 1. Retrieval and Connector Correctness

- [x] Resolve available capabilities from every indexed connector instead of Local Files only.
- [x] Preserve per-event capabilities through retrieval and missing-data calculation.
- [x] Validate local-model evidence IDs and citations before displaying a generated answer.
- [x] Add and enforce the notification application allowlist required by the product spec.
- [x] Scope capability planning to the requested time range and prevent unrelated-event fallback.
- [x] Parse Korean/Japanese intent and approximate-time expressions used by the localized Ask UI.

### 2. Typed External Enrichment

- [x] Implement a fixed weather provider behind `OnlineEnrichmentGateway`.
- [x] Keep map/place and reverse-geocode operations inside the typed gateway.
- [x] Add timeout, schema validation, explicit unavailable results, and network-boundary tests.

The current Open-Meteo public endpoint is suitable only for non-commercial prototype use. Commercial release remains blocked on a paid/fixed provider contract or a replacement provider with compatible terms.

### 3. Automatic Indexing Runtime

- [x] Make connector scan storage atomic, version the SQLCipher schema, and expose a consistent derived-memory snapshot.
- [x] Add a persistent SQLCipher indexing queue with atomic claims, leases, recovery, deduplication, and bounded status retention.
- [x] Route manual and automatic commands through the shared queue executor with trigger-isolated claims and stable outcomes.
- [x] Add unique periodic WorkManager scheduling for enabled automatic indexing.
- [x] Enforce charging, low-usage window, battery, and thermal conditions at runtime.
- [x] Fence canceled and reconfigured workers with a durable SQLCipher control generation.
- [x] Surface queued/running work, latest automatic activity, recent task outcomes, skipped reasons, and failure states in Sources.

### 4. PDF and OCR Indexing

The implemented installer boundary and document-runtime limits are specified in `docs/pdf-ocr.md`.

- [x] Persist latest connector scan status and support atomic snapshot reconciliation for removed pages.
- [x] Store bounded typed scan issue codes and localize them only after reading.
- [x] Install fixed-catalog English, Korean, and Japanese OCR language data only after an explicit user action.
- [x] Add the private separate-process Pdfium/Tesseract runtime with crash containment, hard watchdogs, a bounded AIDL contract, locked dependencies, and packaged notices.
- [x] Accept user-selected PDF documents.
- [x] Extract text and page metadata transiently in the private runtime; render and OCR pages locally when embedded text is unavailable.
- [x] Persist only HMAC source references, derived page summaries, keyword signals, and closed page citations.
- [x] Add document size/page/render/text/OCR/time limits and explicit unsupported/missing-data results.
- [x] Enforce the 10-minute connector scan limit, 128-page aggregate output limit, and atomic full-snapshot replacement.

### 5. Encrypted Export and Import

- [x] Implement password-protected authenticated export containing allowed derived sections only.
- [x] Implement schema/version/integrity validation and transactional import.
- [x] Require connector re-consent after import.
- [x] Add Android document create/open flows without cloud sync or automatic backup.

### 6. Optional App Security

- [x] Add persisted screenshot-blocking preference and apply `FLAG_SECURE` when enabled.
- [x] Add system biometric/device-credential app lock with API-compatible fallback and security-settings recovery.
- [x] Relock on process start and non-configuration background, and fence stale authentication callbacks without a bypass.
- [x] Add JVM and Android lifecycle coverage for secure-window, lock, unlock, failure, rotation, and background transitions.

The lifecycle instrumentation covers persisted secure-window startup, Activity recreation, unlocked-session configuration continuity, ordinary background relock, and protected-content gating. Its source compiles on the host; biometric/PIN system-UI execution, screenshots/recording, process death, and API 26/29/30 device behavior remain in the physical-device acceptance queue in `docs/remaining-work.md` because no device or emulator is connected.

### 7. Source and UI Completion

- [x] Build place history/cluster output from indexed location evidence and populate Places.
- [x] Refresh usage/notification permission state after returning from Android settings.
- [x] Localize connector status, error, and derived-summary presentation.
- [x] Add date-range indexing controls and per-connector date-range status.

Location clusters accumulate user-triggered last-known observations by stable 0.001-degree rounded coordinate; Android does not expose a historical location archive to this connector. Calendar, Photos, and App Usage expose 7-, 30-, and 90-day local-date ranges. The queue stores half-open instant bounds, unsupported or event-driven connectors never receive a range request, and every connector output bound reports a typed partial status instead of claiming full coverage. Sources rechecks usage/notification access exactly once after the corresponding Android settings activity returns and rejects a second in-flight settings launch. Timeline, Places, connector scan status/issues, sensitivity, confidence, capability names, model failures, file sizes, and manual indexing failures are formatted from typed values in English, Korean, or Japanese rather than displaying connector-authored prose or enum/storage keys.

### 8. Local Model Release Hardening

- [x] Reject model downloads before connection unless immutable HTTPS URL, exact byte count, and pinned SHA-256 metadata are complete; reject redirects and publish atomically.
- [x] Add durable model-download generation fencing before enabling a catalog transport entry.
- [x] Implement canonical bounded remote-manifest parsing, ECDSA P-256 verification, compatibility/expiry checks, and durable rollback/equivocation state.
- [x] Fence stale work before any manifest/artifact connection, keep the Settings refresh gate process-wide, and fail closed on malformed response-length metadata.
- [x] Declare the WorkManager model-transfer foreground service and runtime `ForegroundInfo` as `dataSync` for Android 14 compatibility.
- [x] Reject app-private model-path symlinks and bind verified-file cache entries to stable filesystem identity.
- [x] Validate imported LiteRT-LM container structure, version, size/space bounds, exact copy, and atomic publication beyond extension alone.
- [x] Expand held-out synthetic training/evaluation coverage across 10 behavior families and English/Korean/Japanese, then run the deterministic grounded-answer contract benchmark.
- [x] Implement fail-closed local LoRA merge, explicit `dynamic_wi8_afp32` LiteRT export, exact 30-fixture CLI output gate, and provenance/local-manifest tooling without committing artifacts.

Repository tooling prepares and validates a release without committing weights, private keys, credentials, or generated provenance. Actual release execution is tracked in `docs/remaining-work.md`.

### 9. Final Verification

- [x] Run all JVM coverage for policy, queue, crypto, parsing, capability, connector closure, model release, and grounding behavior.
- [x] Compile Android instrumentation coverage for permissions, SQLCipher, document flows, WorkManager, notification filtering, app security, and model storage.
- [x] Run Android lint, debug/release builds, APK boundary inspections, model-training policy gates, and repository diff checks.
- [x] Remove stale roadmap/status text and confirm every repository-enforceable hard constraint.

Host verification completed on 2026-07-15. A clean rerun executed 119 Gradle tasks: all 377 JVM tests passed with zero failures; Android instrumentation sources compiled; debug and unsigned release APKs assembled; lint completed with no errors (76 warnings and 3 hints remain); and the OCR-data, PDF/OCR notice, document-runtime, and debug/release model-artifact APK boundaries passed. The model tooling checked 30 training and 30 held-out records across 10 families and 3 languages, passed all 30 deterministic grounded-answer fixtures and all 46 Python tests, and passed configuration, artifact-policy, release-plan, smoke-plan, bytecode-compilation, and diff checks. An independent final diff review found no remaining high- or medium-severity repository issue.

Android Studio sync compatibility was verified on 2026-07-16 with Gradle 9.5.1 and AGP 8.13.2. Strict checksum verification remains enabled for executable build/runtime dependencies; IDE-only source and Javadoc attachments use the narrow trusted-artifact patterns recommended by the Android dependency-verification guidance.

### 10. Long-Term Movement Foundation

- [x] Promote the original concept-aligned evidence expansion into an explicit staged implementation plan while preserving the non-agentic, local-first, network, consent, and zero-raw-retention invariants.
- [x] Add a pure location-timeline derivation boundary that accepts only 0.001-degree rounded observations, rejects exact coordinates and invalid accuracy, deduplicates stable sources, derives bounded stays and movement legs, and reports coverage gaps instead of claiming movement across long observation gaps.

The next completed movement increment adds a default-OFF, user-started `location` foreground service. It can begin only from the visible app after Location is connected, displays a persistent notification with an immediate Stop action, requests no background-location permission, never auto-starts, and indexes only accepted 15-minute / 250-metre network/GPS observations through the existing 0.001-degree rounded SQLCipher scan boundary. It disables online enrichment on this path and serializes direct observation writes with Location revoke/delete. Movement stays/legs remain transient derivation output. Route-oriented Ask requests now build ephemeral evidence only for adjacent observations less than six hours apart and only when both endpoint citations exist; Timeline and Places route presentation remain pending. Full JVM tests, Android instrumentation-source compilation, lint with no errors, and debug assembly passed on 2026-07-16.

The first visual-understanding foundation adds a closed visual-signal policy: only reviewed `food`, `drink`, `document`, `person-present`, `indoor`, and `outdoor` labels with medium/high confidence can ever be admitted; arbitrary captions, OCR, embeddings, pixels, crops, and model prose have no representation. It is not connected to photo indexing until the fixed reviewed on-device visual-model artifact and the store-validator changes in `docs/remaining-work.md` are completed.

## Remaining Work

All open work, blockers, ordering, completion criteria, and next-session commands live in `docs/remaining-work.md`. Keep this roadmap as completed implementation and verification history; do not duplicate open checklists here.
