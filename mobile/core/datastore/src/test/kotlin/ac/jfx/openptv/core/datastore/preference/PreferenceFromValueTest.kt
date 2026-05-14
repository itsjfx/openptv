package ac.jfx.openptv.core.datastore.preference

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round-trip + fallback tests for the three sealed Preference subclasses. The contract:
 *  - `fromValue(name)` for every known case returns the corresponding `data object`.
 *  - `fromValue(null)` (no stored value yet) falls back to `default`.
 *  - `fromValue("garbage")` (forward-compat: a newer build wrote a case this build doesn't
 *    know) falls back to `default` rather than crashing.
 *
 * These run on the JVM (no Robolectric) — sealed-class behaviour does not need an Android
 * runtime. `DynamicColourPreference.default` *does* read `Build.VERSION.SDK_INT`, which on the
 * JVM resolves to 0; that means `default == Off` here regardless of the host. The test for
 * `DynamicColour` only asserts the round-trip identity for `On` and `Off` explicitly, plus
 * unknown-falls-back-to-default — the platform-specific default selection is exercised by an
 * instrumented test in a future androidTest pass.
 */
class PreferenceFromValueTest {
    @Test
    fun `themeMode fromValue round-trips every known case`() {
        assertThat(ThemeModePreference.fromValue("System")).isEqualTo(ThemeModePreference.System)
        assertThat(ThemeModePreference.fromValue("Light")).isEqualTo(ThemeModePreference.Light)
        assertThat(ThemeModePreference.fromValue("Dark")).isEqualTo(ThemeModePreference.Dark)
    }

    @Test
    fun `themeMode fromValue null falls back to default`() {
        assertThat(ThemeModePreference.fromValue(null)).isEqualTo(ThemeModePreference.default)
    }

    @Test
    fun `themeMode fromValue unknown falls back to default`() {
        assertThat(ThemeModePreference.fromValue("Sepia")).isEqualTo(ThemeModePreference.default)
    }

    @Test
    fun `dynamicColour fromValue On is On and Off is Off`() {
        assertThat(DynamicColourPreference.fromValue("On")).isEqualTo(DynamicColourPreference.On)
        assertThat(DynamicColourPreference.fromValue("Off")).isEqualTo(DynamicColourPreference.Off)
    }

    @Test
    fun `dynamicColour fromValue null falls back to default`() {
        assertThat(DynamicColourPreference.fromValue(null))
            .isEqualTo(DynamicColourPreference.default)
    }

    @Test
    fun `dynamicColour fromValue unknown falls back to default`() {
        assertThat(DynamicColourPreference.fromValue("Maybe"))
            .isEqualTo(DynamicColourPreference.default)
    }

    @Test
    fun `favouritesSort fromValue round-trips every known case`() {
        assertThat(FavouritesSortPreference.fromValue("Manual"))
            .isEqualTo(FavouritesSortPreference.Manual)
        assertThat(FavouritesSortPreference.fromValue("Alphabetical"))
            .isEqualTo(FavouritesSortPreference.Alphabetical)
        assertThat(FavouritesSortPreference.fromValue("Nearest"))
            .isEqualTo(FavouritesSortPreference.Nearest)
    }

    @Test
    fun `favouritesSort fromValue null falls back to default`() {
        assertThat(FavouritesSortPreference.fromValue(null))
            .isEqualTo(FavouritesSortPreference.default)
    }

    @Test
    fun `favouritesSort fromValue unknown falls back to default`() {
        assertThat(FavouritesSortPreference.fromValue("ByRouteId"))
            .isEqualTo(FavouritesSortPreference.default)
    }

    @Test
    fun `favouritesSort default is Manual`() {
        assertThat(FavouritesSortPreference.default).isEqualTo(FavouritesSortPreference.Manual)
    }

    @Test
    fun `themeMode default is System`() {
        assertThat(ThemeModePreference.default).isEqualTo(ThemeModePreference.System)
    }
}
