## GitHub SDLC

GitHub repository: `itsjfx/openptv`

Raise PRs.

## Issue Template

When creating issues use the following template: What, Why, How, Acceptance Criteria, Out of Scope, Definition of Done

## Commits

All commits must include a `Co-Authored-By` trailer for the AI contributor:
```
Co-Authored-By: ai-tiro <ai-tiro@jfx.ac>
```

Make regular, small commits when accomplishing a small milestone within a ticket.

Push to a branch and make a draft PR as soon as possible. When complete, mark the PR as ready to review.

Put in the PR description: what you did, what you discovered, anything you tried that didn't work, and justify why you did what you did. Note any one-way door changes, or testing concerns.

If you're stuck, tag `@itsjfx` on the PR with your query before marking as ready.

When done, assign the PR to `@itsjfx` for review.

## Platform constraint: no Google Play services

The mobile app MUST run on **GrapheneOS**, so it cannot depend on Google Play services (GMS), Firebase, FCM, Play Billing, or anything that requires the Play Store. Use AOSP-friendly alternatives (UnifiedPush instead of FCM, MapLibre/OSM instead of Google Maps, `LocationManager` instead of Fused Location, etc.).

Because of this, the local + CI test target is the **AOSP system image** (`system-images;android-XX;default;x86_64`), not `google_apis*` — that way anything that secretly depends on GMS fails locally just like it would on GrapheneOS.

## Mobile testing workflow

When you make changes to the mobile app, test them on the AOSP emulator via the `mobile-mcp` MCP server before marking the PR ready:

1. **Get an emulator running.** Check for one with `mobile_list_available_devices`. If nothing's there, boot the AOSP AVD (`pixel_api36`); if that AVD doesn't exist, create it from `system-images;android-XX;default;x86_64` and boot it. Launch notes (KVM group, `LD_LIBRARY_PATH`) are in the auto-memory.
2. **Build and install:** `./gradlew :app:assembleDebug` then `adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk`.
3. **Exercise the change** with mobile-mcp tools — `mobile_list_elements_on_screen` to find what to tap, `mobile_click_on_screen_at_coordinates` / `mobile_type_keys` / `mobile_swipe_on_screen` to drive the UI.
4. **Take screenshots** at each meaningful state with `mobile_save_screenshot` to `/tmp/<descriptive-name>.png`, then commit them under `docs/mobile/screenshots/<phase-or-feature>/` and reference them in the PR body so the reviewer can see the change without booting an emulator themselves.
