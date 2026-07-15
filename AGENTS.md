# Repository Guidelines

## Project Structure & Module Organization

Grayin AI is an Android Kotlin/Jetpack Compose app. Core source lives under `app/src/main/java/ai/grayin/`: UI/controller code is in `app/`, shared domain models in `core/model`, connector contracts in `core/connector`, SQLCipher store code in `core/store`, and retrieval/grounding/security/local-AI code in matching `core/*` packages. Real connector paths live under `connectors/*` for Local Files, Location, Photos, Calendar, Notifications, and App Usage. Tests live under `app/src/test`; device/runtime tests belong under `app/src/androidTest`. Product, privacy, architecture, network, and roadmap docs live in `docs/`; benchmark prompts live in `benchmarks/query-set.md`.

## Build, Test, and Development Commands

- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:assembleDebug` builds the debug APK with the local Android SDK.
- `./gradlew :app:installDebug` installs the debug APK on a connected device or emulator.
- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:testDebugUnitTest` runs JVM unit tests.
- `./gradlew :app:lintDebug` runs Android lint checks.

## Coding Style & Naming Conventions

Use Kotlin defaults: 4-space indentation, trailing commas where they reduce diff noise, and concise immutable data classes for domain state. Keep packages under `ai.grayin`. Name Compose screen functions with `PascalCase` plus `Screen` suffix, for example `SourcesScreen`. Keep connector implementations in source-specific packages such as `connectors/photos`. No formatter, ktlint, or detekt config is currently checked in; use Android Studio/Kotlin formatting.

## Testing Guidelines

JVM tests are under `app/src/test`. Name tests after the unit under test, for example `DefaultQueryPlannerTest`. Cover security-sensitive behavior, especially zero-raw-retention, cited evidence filtering, missing-data explanations, and the bounded network policy. Add instrumentation or Compose UI tests under `app/src/androidTest` when device/runtime behavior needs coverage.

## Commit & Pull Request Guidelines

Git history uses short imperative commits such as `Add MVP 8 grounded answer format` and `Complete MVP 13 benchmarks`. Follow that style and keep each commit to one coherent phase. Pull requests should describe scope, list validation commands run, link relevant docs or roadmap items, and include screenshots for UI changes.

## Agent Workflow

Before implementation work, read `docs/product-spec.md`, `docs/roadmap.md`, `docs/network-policy.md`, `docs/zero-raw-retention.md`, and `docs/non-agentic-boundary.md`. Use `docs/roadmap.md` for future work because the completed MVP task list has been removed.

Implement one coherent step at a time. Update affected docs, run available checks or builds, and commit with a clear message.

## Security & Configuration Tips

MVP constraints are hard requirements: network use is limited to typed map/place/reverse-geocode/weather enrichment and fixed-catalog model/manifest downloads defined by `docs/network-policy.md`. No arbitrary or user-supplied URL/endpoint, remote LLM, application backend, cloud sync, account SDK, analytics, ads, telemetry, crash SDK, or raw/original data storage, logging, caching, export, sync, or transmission. Derived memory storage must stay SQLCipher-backed with Android Keystore passphrase protection. When changing behavior, update affected files in `docs/` and keep `docs/roadmap.md` aligned if scope changes.
