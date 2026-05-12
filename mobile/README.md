# OpenPTV — mobile

Android client for the OpenPTV project (see `../docs/architecture.md`). This is the **barebones**
Phase 01 skeleton: a single-module Compose app with a Material You theme and one Home placeholder.
The multi-module split, convention plugins under `build-logic/`, detekt / Spotless / Dependency
Guard, and the GitHub Actions CI workflow described in `../docs/mobile/phase-01-skeleton.md` land
in a follow-up issue.

## Build

```bash
./gradlew :app:assembleDebug
```

Requires the Android SDK installed locally with `ANDROID_HOME` (or `sdk.dir` in `local.properties`)
pointing at it. JDK 21 toolchain; the Gradle wrapper is committed.

## Formatting

Spotless (ktlint + a short Apache-2.0 license header) is wired into every Kotlin module via the
`openptv.spotless` convention plugin — applied transitively through the android / jvm convention
plugins, so individual modules don't need to opt in.

```bash
./gradlew spotlessApply   # rewrite anything off-style in place
./gradlew spotlessCheck   # CI gate; fails on any drift
```

`spotlessApply` should be a no-op on a clean tree. CI runs `spotlessCheck`. The header lives in
`mobile/spotless/license-header.kt`. A pre-commit hook recommendation is documented in
[`../docs/mobile/00-conventions.md`](../docs/mobile/00-conventions.md#pre-commit).
