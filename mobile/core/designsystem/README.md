# :core:designsystem

Owns the `OpenPtvTheme` Compose theme entry point and the project's visual contract.

## Screenshot tests (Roborazzi)

`OpenPtvThemeScreenshotTest` records a 2 x 3 baseline of the theme tokens — Light / Dark x Phone / PhoneLandscape / Tablet. Snapshots are committed under `src/test/snapshots/` so reviewers can sanity-check them in PRs.

### Record (regenerate the baseline)

Run this after any deliberate theme change:

```bash
cd mobile
./gradlew :core:designsystem:recordRoborazziDebug
```

Eyeball the resulting PNGs under `mobile/core/designsystem/src/test/snapshots/` before committing — `record` rewrites whatever is on disk, so it will happily commit a regression if you don't look.

### Verify (the gate)

```bash
cd mobile
./gradlew :core:designsystem:verifyRoborazziDebug
```

Runs in CI on every PR (see `screenshot` job in `.github/workflows/mobile-ci.yml`). On failure, Roborazzi writes side-by-side compare PNGs to `mobile/core/designsystem/build/outputs/roborazzi/`; CI uploads that directory as a `roborazzi-diff` artifact so reviewers can see exactly what shifted.

### Why no dynamic-colour variants

`OpenPtvTheme` switches to `dynamicLightColorScheme` / `dynamicDarkColorScheme` on Android 12+, but Robolectric has no wallpaper to extract a palette from — those calls collapse onto the deterministic Material 3 defaults, producing pixels identical to the static Light / Dark cases. Locking them in would record a duplicate and add no protection. Real-device dynamic colour is exercised manually until #N covers wallpaper palette injection.
