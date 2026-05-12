# OpenPTV — mobile

Android client for the OpenPTV project (see `../docs/architecture.md`). The multi-module split,
convention plugins under `build-logic/`, detekt / Spotless / Dependency Guard, and the GitHub
Actions CI workflow are all wired up — see `../docs/mobile/phase-01-skeleton.md` for the design.

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

## Static analysis

Detekt is wired into every Kotlin module the same way Spotless is — via the `openptv.detekt`
convention plugin applied transitively through the android / jvm convention plugins. The shared
config is `mobile/detekt.yml`; feature modules don't override it (it's the SSOT).

```bash
./gradlew detekt          # aggregated CI gate
./gradlew :feature/search:detekt   # one module
```

Spotless owns formatting (ktlint), detekt owns structure (complexity, naming, magic numbers,
potential bugs). The `formatting` ruleset is deliberately disabled to avoid two tools fighting
over the same edits.

Project-specific rules live in `:lint:detekt` and plug in through detekt's `RuleSetProvider` SPI.
The current rule, `ForbidAndroidLog`, fails the build on any `import android.util.Log` outside
`:core:common.AndroidLogger` — every other caller MUST inject `core.common.Logger`.

## Dependency Guard

Dropbox's [Dependency Guard](https://github.com/dropbox/dependency-guard) locks the transitive
dependency set on `:app`'s `releaseRuntimeClasspath` (i.e. exactly what ends up in the production
APK) against a checked-in baseline at `app/dependencies/releaseRuntimeClasspath.txt`. Compose-BOM
bumps, Retrofit minors, etc. otherwise pull in dozens of artifacts invisibly — particularly
problematic here because the app targets GrapheneOS, so anything transitively dragging in
`com.google.android.gms` / Firebase / Play services must be caught at PR review, not at runtime
on a user's device.

```bash
./gradlew :app:dependencyGuard           # CI gate — verifies against the baseline
./gradlew :app:dependencyGuardBaseline   # rebaseline after intentional dep changes
```

When you rebaseline, call it out explicitly in the PR description — the whole point is that the
new set of artifacts gets a human review, not just a green build.

Deliberately scoped to `releaseRuntimeClasspath` only. Debug / test configurations pull in
MockWebServer, Hilt test infra, Compose tooling etc. and would add noisy baseline churn for no
real shipping-code value. The plugin is applied directly in `:app/build.gradle.kts` (not via a
convention plugin) because `:app` is the rollup that determines the APK — tracking each
`:core:*` / `:feature:*` independently would multiply baseline files without catching anything
the `:app` baseline doesn't already lock down.
