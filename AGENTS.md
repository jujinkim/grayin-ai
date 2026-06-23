# Repository Guidelines

## Project Structure & Module Organization

Grayin AI is an Android Kotlin/Jetpack Compose app. Core source lives under `app/src/main/java/ai/grayin/`: UI entry points are in `app/`, shared domain models in `core/model`, connector contracts in `core/connector`, local store abstractions in `core/store`, indexing/retrieval/grounding/security/local-AI code in their matching `core/*` packages, and source-specific stubs in `connectors/*`. Android resources live in `app/src/main/res`. Product, privacy, and architecture docs live in `docs/`; benchmark prompts live in `benchmarks/query-set.md`.

## Build, Test, and Development Commands

- `ANDROID_HOME=/home/jujin/workspace/android-sdk ANDROID_SDK_ROOT=/home/jujin/workspace/android-sdk ./gradlew :app:assembleDebug` builds the debug APK with the local Android SDK.
- `./gradlew :app:installDebug` installs the debug APK on a connected device or emulator.
- `./gradlew :app:testDebugUnitTest` runs JVM unit tests when `app/src/test` tests exist.
- `./gradlew :app:lintDebug` runs Android lint checks.

## Coding Style & Naming Conventions

Use Kotlin defaults: 4-space indentation, trailing commas where they reduce diff noise, and concise immutable data classes for domain state. Keep packages under `ai.grayin`. Name Compose screen functions with `PascalCase` plus `Screen` suffix, for example `SourcesScreen`. Keep connector implementations in source-specific packages such as `connectors/photos`. No formatter, ktlint, or detekt config is currently checked in; use Android Studio/Kotlin formatting.

## Testing Guidelines

No test source sets are currently present. Add JVM tests under `app/src/test` and instrumentation or Compose UI tests under `app/src/androidTest`. Name tests after the unit under test, for example `QueryPlannerTest`. Cover security-sensitive behavior, especially zero-raw-retention, missing-data explanations, and no-network assumptions.

## Commit & Pull Request Guidelines

Git history uses short imperative commits such as `Add MVP 8 grounded answer format` and `Complete MVP 13 benchmarks`. Follow that style and keep each commit to one coherent phase. Pull requests should describe scope, list validation commands run, link relevant docs or TODO items, and include screenshots for UI changes.

## Security & Configuration Tips

MVP constraints are hard requirements: no `android.permission.INTERNET`, no server/cloud/account SDKs, no analytics or crash SDKs, and no raw/original data storage, logging, caching, export, sync, or transmission. When changing behavior, update affected files in `docs/` and keep `docs/mvp-todo.md` aligned.
