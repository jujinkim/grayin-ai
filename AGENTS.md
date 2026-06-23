# Repository Guidelines

## Project Structure & Module Organization

Grayin AI is an Android Kotlin/Jetpack Compose app. Core source lives under `app/src/main/java/ai/grayin/`: UI/controller code is in `app/`, shared domain models in `core/model`, connector contracts in `core/connector`, SQLCipher store code in `core/store`, and retrieval/grounding/security/local-AI code in matching `core/*` packages. Local Text/Markdown indexing is in `connectors/localfiles`; other connectors remain stubs. Tests live under `app/src/test`. Product, privacy, architecture, and roadmap docs live in `docs/`; benchmark prompts live in `benchmarks/query-set.md`.

## Build, Test, and Development Commands

- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:assembleDebug` builds the debug APK with the local Android SDK.
- `./gradlew :app:installDebug` installs the debug APK on a connected device or emulator.
- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:testDebugUnitTest` runs JVM unit tests.
- `./gradlew :app:lintDebug` runs Android lint checks.

## Coding Style & Naming Conventions

Use Kotlin defaults: 4-space indentation, trailing commas where they reduce diff noise, and concise immutable data classes for domain state. Keep packages under `ai.grayin`. Name Compose screen functions with `PascalCase` plus `Screen` suffix, for example `SourcesScreen`. Keep connector implementations in source-specific packages such as `connectors/photos`. No formatter, ktlint, or detekt config is currently checked in; use Android Studio/Kotlin formatting.

## Testing Guidelines

JVM tests are under `app/src/test`. Name tests after the unit under test, for example `DefaultQueryPlannerTest`. Cover security-sensitive behavior, especially zero-raw-retention, cited evidence filtering, missing-data explanations, and no-network assumptions. Add instrumentation or Compose UI tests under `app/src/androidTest` when device/runtime behavior needs coverage.

## Commit & Pull Request Guidelines

Git history uses short imperative commits such as `Add MVP 8 grounded answer format` and `Complete MVP 13 benchmarks`. Follow that style and keep each commit to one coherent phase. Pull requests should describe scope, list validation commands run, link relevant docs or roadmap items, and include screenshots for UI changes.

## Security & Configuration Tips

MVP constraints are hard requirements: `android.permission.INTERNET` is allowed only through typed online enrichment methods, no arbitrary URL/endpoint caller, no server/cloud/account SDKs, no analytics or crash SDKs, and no raw/original data storage, logging, caching, export, sync, or transmission. Derived memory storage must stay SQLCipher-backed with Android Keystore passphrase protection. When changing behavior, update affected files in `docs/` and keep `docs/roadmap.md` aligned if scope changes.
