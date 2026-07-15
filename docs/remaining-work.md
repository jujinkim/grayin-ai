# Remaining Work

This file is the single source of truth for open Grayin AI work.

`docs/roadmap.md` records the completed repository implementation and verification history. Product, privacy, security, and component documents define invariants. Do not copy open checklists back into those files. When an item here is completed, remove it from this queue and record the durable result in the affected specification plus `docs/roadmap.md`.

## Current Verified Baseline

- Repository implementation is complete through roadmap Step 9.
- Host-verified implementation baseline: commit `5a9b3d9` on 2026-07-15.
- Host verification passed 377 JVM tests, Android instrumentation source compilation, lint with no errors, debug/release assembly, APK boundary checks, 30 deterministic grounded-answer fixtures, and 46 model-tooling tests.
- Lint still reports 76 warnings and 3 hints; these are tracked below as release-quality work, not hidden implementation failures.
- No Android device or emulator was connected for the baseline verification.
- Production model transport remains fail-closed: model catalog download URLs, exact sizes, digests, production manifest endpoint, and production public key are not configured.
- Inactive model transport metadata is defined in `app/src/main/java/ai/grayin/core/ai/ModelCatalog.kt`; production endpoint/key configuration is defined by `RemoteModelManifestConfiguration` in `app/src/main/java/ai/grayin/core/ai/SignedModelManifest.kt`.
- Generated weights, adapters, `.litertlm` files, predictions, provenance, private keys, credentials, and signing material must remain outside git.

## Update Discipline

1. Start every new work session by reading this file plus the invariant documents listed in `AGENTS.md`.
2. Work top-down unless the user explicitly changes priority.
3. Keep external blockers marked as blockers; never replace missing artifacts, contracts, keys, credentials, or device evidence with placeholders.
4. Complete one coherent item, update affected docs, run its validation, and commit it. Check completed substeps while a numbered item remains active.
5. When a full numbered item is complete, remove it from this file. Record lasting behavior and verification in the owning document and `docs/roadmap.md`.
6. Never commit secrets, private keys, release keystores, provider credentials, user data, model weights, or generated model artifacts.

## Next Session Start

```bash
git status -sb
git log -1 --oneline
sed -n '1,260p' docs/remaining-work.md
/home/jujin/workspace/android-sdk/platform-tools/adb devices
```

If the working tree is clean, choose the first unblocked item below. Do not reopen completed roadmap Steps 0–9 unless current code or validation proves a regression.

## P0 — External Operational Release Blockers

These tasks require contracts, artifacts, credentials, infrastructure, or external key custody. Repository code must remain fail-closed until each task's inputs are real and reviewed.

### 0.1 Select production enrichment providers

Status: `BLOCKED_EXTERNAL`

- [ ] Decide whether production reverse geocoding will keep Android `Geocoder` or use a reviewed fixed provider; review service availability, platform/provider terms, regions, retention, and failure behavior.
- [ ] Select a commercial-compatible weather provider or paid Open-Meteo contract.
- [ ] Review weather data terms, attribution, retention, regions, quotas, availability, and required request fields.
- [ ] Confirm Grayin can keep requests inside the existing typed `OnlineEnrichmentGateway` and minimal coordinate/date projection.
- [ ] Update the fixed adapter, user disclosure, privacy model, threat model, and network policy.
- [ ] Add provider contract tests for fixed hosts/paths, request minimization, response bounds, timeout, redirect rejection, and typed failure.

Done when:

- production use is contractually allowed,
- no secret is embedded as if an APK could protect it,
- provider configuration cannot create an arbitrary endpoint caller,
- local coordinate-only indexing still succeeds when enrichment fails,
- relevant JVM tests, lint, and debug/release builds pass.

### 0.2 Approve real model-release inputs

Status: `BLOCKED_EXTERNAL`

- [ ] Approve exact Gemma base-model revision and license.
- [ ] Approve training corpus, LoRA configuration, and their exact hashes.
- [ ] Acquire and independently verify the approved base-model tree digest.
- [ ] Prepare a hermetic release environment with reviewed pinned `litert-torch` and `litert-lm` executables.
- [ ] Record executable versions and SHA-256 digests outside source-controlled secrets or artifacts.

Done when every pre-training input has an immutable identity, review owner, license decision, and reproducible digest.

### 0.3 Build and qualify real Grayin model

Status: `BLOCKED_EXTERNAL`

- [ ] Train the real LoRA adapter using approved inputs.
- [ ] Compute the resulting adapter tree digest, independently review the output, and approve that exact digest before merge.
- [ ] Merge LoRA into the approved local Gemma 4 base with expected base/adapter tree digests.
- [ ] Export `.litertlm` with explicit `dynamic_wi8_afp32` quantization and pinned local chat template.
- [ ] Run `litert-lm` against all 30 held-out fixtures; accept only exact four-line grounded outputs that pass every deterministic metric.
- [ ] Generate local merge, export, evaluation, and release provenance.
- [ ] Independently review quality, license, digest, size, and provenance.

Relevant commands:

```bash
make -f model-training/Makefile corpus-check validate eval test artifact-policy
python3 model-training/scripts/download_reference_model.py --dry-run
python3 model-training/scripts/download_reference_model.py
make -f model-training/Makefile train
make -f model-training/Makefile merge-input-digests
make -f model-training/Makefile merge BASE_TREE_SHA256="$APPROVED_BASE_TREE_SHA256" ADAPTER_TREE_SHA256="$APPROVED_ADAPTER_TREE_SHA256" SOURCE_DATE_EPOCH="$APPROVED_SOURCE_DATE_EPOCH"
make -f model-training/Makefile download-export-template
make -f model-training/Makefile export LITERT_TORCH="$PINNED_LITERT_TORCH" LITERT_TORCH_SHA256="$APPROVED_LITERT_TORCH_SHA256"
make -f model-training/Makefile litert-eval LITERT_LM="$PINNED_LITERT_LM" LITERT_LM_SHA256="$APPROVED_LITERT_LM_SHA256"
make -f model-training/Makefile local-release-manifest
```

Set every referenced environment variable to a reviewed non-empty value first. Run reference-model and template downloads only in the authorized release-preparation environment; run merge, export, and evaluation in the reviewed network-isolated environment.

Done when one reviewed `.litertlm` passes the full real CLI gate and its exact size, SHA-256, provenance, license, and supported runtime identity are approved.

### 0.4 Publish immutable model artifact

Status: `BLOCKED_EXTERNAL`

- [ ] Select production object storage/CDN host.
- [ ] Publish the accepted artifact at an immutable HTTPS URL without user information, query, fragment, custom port, or redirects.
- [ ] Publish final license/terms URL.
- [ ] Independently verify remote byte count and SHA-256 against accepted local release.
- [ ] Define replacement and deprecation policy without mutating an existing release URL.

Done when the fixed artifact metadata can be inserted into a signed manifest without weakening `docs/network-policy.md`.

### 0.5 Activate signed production manifest

Status: `BLOCKED_EXTERNAL`

- [ ] Establish external ECDSA P-256 private-key custody and rotation procedure.
- [ ] Bundle only reviewed public-key material and fixed manifest endpoint in the app.
- [ ] Sign canonical release payload with monotonic sequence, bounded validity, compatibility, artifact, license, replacement, and deprecation fields.
- [ ] Verify refresh, rollback rejection, same-sequence equivocation rejection, key rotation, and exact active-catalog projection through host integration tests.
- [ ] Prepare the signed release and fixed transport configuration for the device download/install/inference acceptance owned by section 1.5.
- [ ] Confirm private key, credentials, and generated signed-release working files remain outside git.

Done when production trust configuration no longer returns `NOT_CONFIGURED`, the signed manifest projects exactly the reviewed Grayin entry under host tests, and the release is ready for section 1.5 device acceptance. Section 1.5 owns the authenticated download, atomic install, and grounded local-inference verdict.

## P1 — Physical-Device Acceptance

Status: `READY_WHEN_HARDWARE_AVAILABLE`

Use representative physical devices across supported Android behavior boundaries. Include API 26, 29, 30, and 34 where practical; record device model, OS/API, app commit, and result without recording user source content.

### 1.1 Connector permission and source flows

- [ ] Location runtime permission, app consent, enrichment consent, revoke, delete, and no-last-known-location state.
- [ ] Calendar permission, empty range, unavailable provider, bounded range, revoke, and delete.
- [ ] Photos permission on pre-Android-14 and Android 14+, selected-photo-only access, reselection, authoritative empty replacement, unavailable provider preservation, revoke, and delete.
- [ ] Usage Access settings return, completed sessions, limited-history disclosure, unavailable provider preservation, revoke, and delete.
- [ ] Notification Listener settings return, package allowlist, event delivery, OTP/security discard, oversized input discard, revoke, and delete.

### 1.2 Local Files, PDF, and OCR

- [ ] Text, Markdown, and PDF picker selection with persisted SAF read grants.
- [ ] Grant revocation, document deletion, narrower PDF page replacement, and full connector revoke.
- [ ] Embedded PDF text path.
- [ ] Installed `eng`, `kor`, and `jpn` OCR paths.
- [ ] Missing pack, unsupported PDF, page/render/text/output limits, cancellation, soft timeout, hard watchdog, Binder death, and `:document` process restart.
- [ ] Confirm no URI, file name, PDF bytes, bitmap, extracted text, or OCR transcript enters logs, SQLCipher, WorkData, export, backup, or network.

### 1.3 Automatic indexing runtime

- [ ] Charging-only behavior.
- [ ] Battery-not-low, storage-not-low, thermal, and configured low-usage-window checks.
- [ ] Cross-midnight window identity and duplicate prevention.
- [ ] Settings disable/reconfigure fencing, process restart, lease expiry, retry, and recovery.
- [ ] Manual indexing remains available when automatic policy skips work.

### 1.4 Optional app security

- [ ] Screenshot and screen-recording blocking with `FLAG_SECURE` on supported devices.
- [ ] Biometric and device-credential success, cancel, failure, unavailable, and enrollment recovery.
- [ ] API 26–29 credential handoff and API 30+ combined prompt.
- [ ] Process start locked state, process death, ordinary background relock, rotation continuity, stale callback rejection, and protected-content gating.

### 1.5 Real local-model runtime

Downloaded-model acceptance depends on completed P0 items 0.3–0.5. Manual-import runtime checks may start earlier with an independently verified local artifact.

- [ ] Initialize accepted `.litertlm` through pinned LiteRT-LM runtime.
- [ ] Measure first-token latency, total latency, peak memory, sustained thermal behavior, and failure fallback on representative RAM tiers.
- [ ] Run grounded quality cases in English, Korean, and Japanese.
- [ ] Verify downloaded, manually imported, deleted, replaced, corrupted, and insufficient-space flows.
- [ ] If a future pinned runtime exposes authenticated family/variant identity, add and test that probe; until then keep UI wording limited to structural compatibility.

Device test entry commands:

```bash
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:installDebug
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:connectedDebugAndroidTest
```

Done when every applicable item passes on the recorded device matrix and failures preserve security, zero-raw-retention, consent, and prior-snapshot guarantees.

## P2 — Release Quality and Distribution

These tasks do not reopen repository feature implementation. Complete before public production distribution.

### 2.1 Resolve lint backlog

Baseline: 76 warnings and 3 hints; no lint errors. Largest groups are `UseKtx` (49), dependency/version notices (14), and `InlinedApi` (6).

- [ ] Review correctness/API warnings first; fix or narrowly document intentional suppressions.
- [ ] Add a real launcher icon and resolve `MissingApplicationIcon`.
- [ ] Review dependency and Android Gradle Plugin upgrades without unreviewed lock or verification-metadata regeneration.
- [ ] Resolve safe KTX, allocation, and platform-API findings.
- [ ] Reach zero unexplained correctness/security warnings; record any accepted non-actionable version hints.

Validation:

```bash
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:lintDebug
```

### 2.2 Prepare signed application release

- [ ] Confirm or update production `versionCode` and `versionName` from current `1` / `0.1.0` baseline; keep every published `versionCode` monotonic.
- [ ] Configure release signing through external/local secret material; never commit keystore or passwords.
- [ ] Build and verify signed AAB/APK.
- [ ] Re-run no-model, no-OCR-data, document-runtime, notice, backup, permission, and network-boundary inspections on final release artifact.
- [ ] Confirm Play Console foreground-service declarations, data-safety answers, privacy-policy URL, provider attribution, store copy, and screenshots match actual behavior.

Done when a signed production artifact passes all host and device gates and its store disclosures match `docs/privacy-model.md` and `docs/network-policy.md`.

## P3 — Explicitly Deferred Product Scope

Status: `DEFERRED_POLICY_CHANGE_REQUIRED`

Do not implement these as opportunistic follow-ups. Each requires product, permission, provenance, privacy, security, zero-raw-retention, retrieval, UI, and test design before code.

- [ ] Photo pixel understanding, visual captions, and visual-content clusters.
- [ ] Historical location source beyond last-known observations explicitly seen by Grayin scans.
- [ ] Calls, messages, browser history, audio, and video connectors.
- [ ] Cloud sync, accounts, application backend, analytics, ads, crash reporting, remote LLMs, or agentic actions.

Any P3 proposal must revise scope and hard boundaries explicitly before moving into P0–P2.

## Host Regression Gate

Run after any repository change that affects code, build, dependencies, manifests, privacy boundaries, or release behavior:

```bash
ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleRelease \
  :app:verifyDebugApkNoBundledOcrData \
  :app:verifyDebugApkPdfOcrNotices \
  :app:verifyDebugApkDocumentBoundary \
  :app:verifyDebugApkNoModelArtifacts \
  :app:verifyReleaseApkNoModelArtifacts
make -f model-training/Makefile corpus-check validate eval test artifact-policy release-plan litert-smoke-plan
python3 -m py_compile model-training/scripts/*.py model-training/tests/*.py
git diff --check
```

If a change does not touch model tooling, its model gate may remain at the last recorded verified baseline. State that explicitly in the commit or handoff.
