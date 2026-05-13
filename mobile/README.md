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

## Modules

The project is a multi-module Gradle build following Android's three-layer architecture.

- **`:app`** — application module. Composition root; hosts the entry-point Activity and the top-level navigation graph.
- **`:feature:*`** — user-facing screens. One module per feature; each owns its own ViewModel, UI, and feature-local resources.
- **`:core:*`** — shared libraries used by `:app` and `:feature:*`. Split by concern: data layer (repository contracts and implementations), design system, domain models, navigation, network, and shared test infrastructure.
- **`:lint:detekt`** — project-specific detekt rules. Plugs into detekt's `RuleSetProvider` SPI.
- **`:ui-test-hilt-manifest`** — minimal Hilt-aware Activity that Compose UI tests host themselves in.

Dependency direction is strictly top-down: `:app` → `:feature:*` → `:core:*`. Feature modules don't depend on each other; cross-feature navigation goes through a shared `:core` module.

Convention plugins live under `build-logic/` and apply shared build config (SDK levels, JVM target, Compose, Hilt) so individual module build files stay small.
