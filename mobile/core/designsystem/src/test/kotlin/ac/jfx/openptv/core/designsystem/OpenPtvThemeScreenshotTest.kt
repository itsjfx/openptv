/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.designsystem

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baseline for `OpenPtvTheme`.
 *
 * Runs the [ThemeGallery] across a 2 x 3 matrix:
 *
 *  - **Themes**: `ThemeMode.Light`, `ThemeMode.Dark`.
 *
 *    Dynamic-colour variants (`DynamicLight` / `DynamicDark`) are intentionally
 *    omitted: on Robolectric there is no wallpaper-extracted palette, so
 *    `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` collapse onto a
 *    deterministic but visually-identical-to-static Material 3 default. Locking
 *    them in would record a duplicate of Light/Dark and add no protection
 *    against real-device drift. Documented as a sensible deviation in the
 *    issue #16 PR.
 *
 *  - **Devices**: `phone` (Pixel 6 portrait), `phone_landscape` (Pixel 6
 *    landscape), `tablet` (Nexus 9-ish 1024x768 mdpi).
 *
 * SDK is pinned to 33 because the current Compose BOM's tooling preview
 * harness exercises API 33 cleanly and Robolectric supports it well. Bumping
 * to 34/35 changes nothing for these widgets visually; if Roborazzi starts
 * shipping shadows for higher SDKs first, we re-record.
 *
 * Output: PNGs land under `src/test/snapshots/<test-class-FQN>.<method>_<theme>_<device>.png`
 * via Roborazzi's default naming. Reference baseline is recorded with
 * `./gradlew :core:designsystem:recordRoborazziDebug` and verified on every
 * push by `./gradlew :core:designsystem:verifyRoborazziDebug` (and by the
 * `screenshot` CI job).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK])
class OpenPtvThemeScreenshotTest(
    private val theme: ThemeCase,
    private val device: DeviceCase,
) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun themeGallery() {
        // Robolectric reads qualifiers per-test via `setQualifiers`; using the
        // `+` prefix layers on top of the SDK-derived defaults instead of
        // wiping the locale / density information Compose's resource loader
        // expects to find.
        RuntimeEnvironment.setQualifiers("+${device.qualifiers}")

        composeRule.setContent {
            OpenPtvTheme(themeMode = theme.mode) {
                ThemeGallery()
            }
        }

        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/theme_${theme.slug}_${device.slug}.png",
        )
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_{1}")
        fun parameters(): List<Array<Any>> = ThemeCase.values().flatMap { t ->
            DeviceCase.values().map { d -> arrayOf<Any>(t, d) }
        }
    }
}

/** Class-level `@Config(sdk = [...])` only accepts `IntArray`-compatible literals. */
private const val SCREENSHOT_SDK = 33

/** Theme cases exercised by the screenshot suite. See class KDoc for why dynamic is skipped. */
enum class ThemeCase(val mode: ThemeMode, val slug: String) {
    Light(ThemeMode.Light, "light"),
    Dark(ThemeMode.Dark, "dark"),
}

/**
 * Locked Robolectric device qualifiers. Strings are layered onto Robolectric's
 * SDK-derived defaults with a leading `+` in [OpenPtvThemeScreenshotTest], so
 * each entry only spells out the dimensions / orientation / density bits we
 * actually want to pin.
 *
 * Robolectric's qualifier parser is order-sensitive and rejects every value
 * that doesn't appear in Android's qualifier table (e.g. trailing `keyshidden`
 * is rejected on its own). We keep the strings minimal — just width/height/
 * orientation/density — and let Robolectric infer the rest from the SDK.
 *
 *   - `Phone`          — Pixel-ish phone: 411 x 914 dp, xhdpi, portrait.
 *   - `PhoneLandscape` — same dimensions rotated: 914 x 411 dp, xhdpi.
 *   - `Tablet`         — Nexus 9-ish: 1024 x 768 dp, xhdpi, xlarge land.
 *
 * The `slug` is the snake-case form used in the output filename so reviewers
 * can scan `theme_light_phone_landscape.png` at a glance.
 */
enum class DeviceCase(val qualifiers: String, val slug: String) {
    Phone(
        qualifiers = "w411dp-h914dp-port-xhdpi",
        slug = "phone",
    ),
    PhoneLandscape(
        qualifiers = "w914dp-h411dp-land-xhdpi",
        slug = "phone_landscape",
    ),
    Tablet(
        qualifiers = "w1024dp-h768dp-xlarge-land-xhdpi",
        slug = "tablet",
    ),
}
