# Mobile Phase 11 — Polish, accessibility, performance

> Goal: ship-quality. This is the last "feature" phase; from here it's bugfixes and small additions.

**Depends on:** Phases 1–10. Run after the feature surface is complete.

## Scope

A grab-bag of cross-cutting work that's easier to do once than per-phase. Treat it as a checklist rather than a sequence.

## Accessibility

- [ ] Every interactive element has a `Modifier.semantics { contentDescription = ... }` or text label.
- [ ] Minimum 48dp touch targets enforced (lint rule).
- [ ] TalkBack pass on every screen — every action is discoverable, every list item announces meaningfully.
- [ ] Large fonts (200%): no clipped text, no unreachable buttons.
- [ ] High-contrast mode: theme colour ratios meet WCAG AA (≥4.5 for body text, ≥3 for large).
- [ ] Reduced-motion preference respected (skip enter/exit transitions).

## Performance

- [ ] Generate a Baseline Profile via `androidx.benchmark:benchmark-macro-junit4`. Cover: cold start → home, search → stop detail, open map.
- [ ] Macrobenchmark cold-start P50 < 500 ms on a Pixel 6, < 1 s on a 2018-era device.
- [ ] Frame drops < 1% on map pan, < 0.5% on departures list.
- [ ] APK size budget: < 8 MB universal, < 4 MB per ABI split (release, R8 full).

## Crash reporting

- [ ] Decide: Sentry self-hosted vs Acra vs none.
- [ ] If yes, opt-in toggle in settings; default off, prominent rationale.

## Localisation

- [ ] Externalise every user-facing string (lint).
- [ ] English (Australia) baseline. `values-en` + `values-en-rAU` only at first.
- [ ] Date/time formatting via `java.time.format.DateTimeFormatter` or Android's `DateUtils.getRelativeTimeSpanString` — locale-aware.

## App polish

- [ ] App icon (foreground + background, monochrome for themed icons).
- [ ] Splash screen via `androidx.core.splashscreen` matching theme.
- [ ] Per-favourite alias names ("Home", "Work").
- [ ] Multi-favourite carousel widget variant (the Phase 7 widget shows one stop; this adds a small swipe-able layout for users with multiple favourites).

## Distribution

- [ ] Release signing config from environment / `local.properties` (never committed).
- [ ] GitHub Releases workflow: tag-triggered, builds APK, publishes with auto-changelog.
- [ ] `fastlane/metadata/` populated for future F-Droid PR.

## Out of scope

- F-Droid submission itself (separate task once distribution is stable).
- Analytics. We don't.
- Per-stop arrival countdown ringtones. No.
