# Roadmap

Current phase status: local-first MVP foundation and basic Android source connectors are implemented. Completion work below tracks production correctness, remaining features, and hardening.

## Current Capability

- User-selected Text, Markdown, and PDF documents through Android document picker, with page-level PDF indexing and local OCR fallback.
- User-connected Android last-known location samples through runtime location permission.
- User-connected Android calendar events through runtime calendar read permission.
- User-connected Android photo metadata through runtime media read permission.
- User-connected Android app usage summaries through usage-access settings.
- User-connected Android notification-derived signals through notification-listener settings access.
- SQLCipher-backed derived-memory store protected by Android Keystore.
- Ask flow over cited local evidence with confidence and missing-data output.
- Local Gemma LiteRT-LM answer adapter over retrieved `EvidencePack`, with template fallback when the model file is unavailable.
- Runtime local-model catalog for Grayin dedicated model placeholder, official Gemma 4 E2B, and official Gemma 4 E4B.
- Fail-closed WorkManager model-download infrastructure using the shared fixed-artifact verifier; all current model network entries remain disabled until immutable URL, exact byte count, and SHA-256 metadata exist.
- App-specific model training scaffold under `model-training/`, with Gemma/reference/output model artifacts excluded from git.
- First-launch Sources intro explaining that user-connected sources must be indexed before Ask can use them.
- Sources UI is backed by connector metadata and permission/index state.
- Sources UI exposes top-level Index all now, persisted automatic indexing settings, and live queue/runtime status with localized outcomes.
- Localized UI copy for system, Korean, English, and Japanese language settings.
- Bottom navigation with icons and localized labels.
- Settings shows local model selection, official model pages, local Gemma status, current adb install path, and `.litertlm` import/delete fallback controls. Catalog download/cancel/delete actions render only after a model entry has complete reviewed transport metadata; none currently does.
- Settings installs, cancels, and deletes fixed-catalog English, Korean, and Japanese OCR language data only after an explicit user action; document indexing never initiates a download.
- Local Files passes explicitly selected PDF descriptors to a private `:document` Pdfium/Tesseract runtime, which enforces descriptor, signature, page, bitmap, text, OCR, and time limits and returns only bounded derived AIDL results.
- Local document selection stores only Keystore HMAC markers. SQLCipher schema v6 removes legacy URI/name-based Local Files graphs before HMAC-only reindexing.
- Settings provides explicit local-only encrypted export/import of the validated seven-section derived snapshot. Version 1 uses password-derived AES-256-GCM, replace-only SQLCipher import, and mandatory connector re-consent.
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

The installer boundary and planned document-runtime limits are specified in `docs/pdf-ocr.md`.

- [x] Persist latest connector scan status and support atomic snapshot reconciliation for removed pages.
- [x] Store bounded typed scan issue codes and localize them only after reading.
- [x] Install fixed-catalog English, Korean, and Japanese OCR language data only after an explicit user action.
- [x] Add the private separate-process Pdfium/Tesseract runtime with crash containment, hard watchdogs, a bounded AIDL contract, locked dependencies, and packaged notices.
- [x] Accept user-selected PDF documents.
- [x] Extract text and page metadata transiently in the private runtime; render and OCR pages locally when embedded text is unavailable.
- [x] Persist only HMAC source references, derived page summaries, keyword signals, and closed page citations.
- [x] Add document size/page/render/text/OCR/time limits and explicit unsupported/missing-data results.
- [x] Enforce the 10-minute connector scan limit, 128-page aggregate output limit, and atomic full-snapshot replacement.
- [ ] Run device/emulator acceptance for embedded text, installed-pack OCR, cancellation, timeout, and `:document` process-death recovery.

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

The lifecycle instrumentation covers persisted secure-window startup, Activity recreation, unlocked-session configuration continuity, ordinary background relock, and protected-content gating. Its source compiles on the host; biometric/PIN system-UI execution, screenshots/recording, process death, and API 26/29/30 device behavior remain part of Step 9 device acceptance because no device or emulator is connected.

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
- [ ] Configure a reviewed production P-256 public key and fixed manifest endpoint, then verify a real signed release manifest end to end.
- [x] Validate imported LiteRT-LM container structure, version, size/space bounds, exact copy, and atomic publication beyond extension alone.
- [ ] Verify an imported model's exact family/variant and successful engine initialization if the pinned LiteRT-LM API exposes an authenticated identity probe; until then the UI must disclose structural compatibility only.
- [x] Expand held-out synthetic training/evaluation coverage across 10 behavior families and English/Korean/Japanese, then run the deterministic grounded-answer contract benchmark.
- [ ] Run the same grounded-answer benchmark against predictions from the trained/exported release model and record device quality, latency, and memory results.
- [ ] Merge/export the release adapter to `.litertlm` outside git.
- [ ] Publish the Grayin model to an immutable external artifact URL and configure catalog metadata.

External artifact publication requires a selected host, release credentials, license/terms URL, production public/private signing-key custody, fixed manifest endpoint, and final model file. Repository work must prepare and validate the release without committing weights, private keys, or credentials.

### 9. Final Verification

- [ ] Add JVM coverage for policy, queue, crypto, parsing, capability, and grounding behavior.
- [ ] Add Android instrumentation tests for permissions, SQLCipher, document flows, WorkManager, notification filtering, and app security.
- [ ] Run unit tests, Android lint, debug/release builds, security scans, and device smoke tests.
- [ ] Remove stale roadmap/status text and confirm all hard constraints.
