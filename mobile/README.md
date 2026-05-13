# OpenPTV — mobile

Android client for the OpenPTV project. The detailed architecture spec lives in [`../docs/architecture.md`](../docs/architecture.md); this README covers the shape of the Gradle build and the things developers need at a glance.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires the Android SDK installed locally with `ANDROID_HOME` (or `sdk.dir` in `local.properties`) pointing at it. JDK 21 toolchain; the Gradle wrapper is committed.

## Test

```bash
./gradlew test
```

## Formatting

Spotless (ktlint) is wired into every Kotlin module via the `openptv.spotless` convention plugin —
applied transitively through the android / jvm convention plugins, so individual modules don't
need to opt in.

```bash
./gradlew spotlessApply   # rewrite anything off-style in place
./gradlew spotlessCheck   # CI gate; fails on any drift
```

`spotlessApply` should be a no-op on a clean tree. CI runs `spotlessCheck`. A pre-commit hook
recommendation is documented in
[`../docs/mobile/00-conventions.md`](../docs/mobile/00-conventions.md#pre-commit).

## Modules

The project is a multi-module Gradle build following Android's three-layer architecture.

- **`:app`** — application module. Composition root; hosts the entry-point Activity and the top-level navigation graph.
- **`:feature:*`** — user-facing screens. One module per feature; each owns its own ViewModel, UI, and feature-local resources.
- **`:core:*`** — shared libraries used by `:app` and `:feature:*`. Split by concern: data layer (repository contracts and implementations), design system, domain models, navigation, network, and shared test infrastructure.
- **`:ui-test-hilt-manifest`** — minimal Hilt-aware Activity that Compose UI tests host themselves in.

Dependency direction is strictly top-down: `:app` → `:feature:*` → `:core:*`. Feature modules don't depend on each other; cross-feature navigation goes through a shared `:core` module.

Convention plugins live under `build-logic/` and apply shared build config (SDK levels, JVM target, Compose, Hilt) so individual module build files stay small.
