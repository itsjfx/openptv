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
