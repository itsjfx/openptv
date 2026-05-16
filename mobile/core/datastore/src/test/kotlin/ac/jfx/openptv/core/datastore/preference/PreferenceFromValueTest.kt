package ac.jfx.openptv.core.datastore.preference

import ac.jfx.openptv.core.model.RouteType
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

    @Test
    fun `timeFormat fromValue round-trips every known case`() {
        assertThat(TimeFormatPreference.fromValue("System")).isEqualTo(TimeFormatPreference.System)
        assertThat(TimeFormatPreference.fromValue("TwelveHour"))
            .isEqualTo(TimeFormatPreference.TwelveHour)
        assertThat(TimeFormatPreference.fromValue("TwentyFourHour"))
            .isEqualTo(TimeFormatPreference.TwentyFourHour)
    }

    @Test
    fun `timeFormat fromValue null falls back to default`() {
        assertThat(TimeFormatPreference.fromValue(null)).isEqualTo(TimeFormatPreference.default)
    }

    @Test
    fun `timeFormat fromValue unknown falls back to default`() {
        // Forward-compat: a newer build that adds a `LocaleAware` case must not crash older
        // installs that don't know the string.
        assertThat(TimeFormatPreference.fromValue("LocaleAware"))
            .isEqualTo(TimeFormatPreference.default)
    }

    @Test
    fun `timeFormat default is System`() {
        assertThat(TimeFormatPreference.default).isEqualTo(TimeFormatPreference.System)
    }

    // -------------------- MapRouteTypeFilterPreference (issue #112) --------------------
    //
    // Wire format: stringified RouteType.toCode() ints in a Set<String>. The persister drops
    // Unknown defensively; the parser drops unknown codes (forward-compat) and falls back to
    // default if the resulting set is empty (preserves the "filter is never empty" invariant the
    // ViewModel enforces at runtime).

    @Test
    fun `mapRouteTypeFilter fromValue parses every known wire code`() {
        // 0=Train, 1=Tram, 2=Bus, 3=VLine, 4=NightBus — see RouteType.toCode().
        val parsed = MapRouteTypeFilterPreference.fromValue(setOf("0", "1", "2", "3", "4"))
        assertThat(parsed.value).containsExactly(
            RouteType.Train,
            RouteType.Tram,
            RouteType.Bus,
            RouteType.VLine,
            RouteType.NightBus,
        )
    }

    @Test
    fun `mapRouteTypeFilter fromValue parses a non-default subset`() {
        val parsed = MapRouteTypeFilterPreference.fromValue(setOf("1"))
        assertThat(parsed.value).containsExactly(RouteType.Tram)
    }

    @Test
    fun `mapRouteTypeFilter fromValue null falls back to default`() {
        assertThat(MapRouteTypeFilterPreference.fromValue(null))
            .isEqualTo(MapRouteTypeFilterPreference.default)
    }

    @Test
    fun `mapRouteTypeFilter fromValue empty set falls back to default`() {
        // Invariant: the filter is never empty. An empty persisted set must restore default.
        assertThat(MapRouteTypeFilterPreference.fromValue(emptySet()))
            .isEqualTo(MapRouteTypeFilterPreference.default)
    }

    @Test
    fun `mapRouteTypeFilter fromValue drops unknown wire codes`() {
        // A newer build wrote a code (e.g. "99" for a future mode) this build doesn't know —
        // drop it silently rather than crash. The known entries still come through.
        val parsed = MapRouteTypeFilterPreference.fromValue(setOf("0", "99"))
        assertThat(parsed.value).containsExactly(RouteType.Train)
    }

    @Test
    fun `mapRouteTypeFilter fromValue drops unparseable entries`() {
        val parsed = MapRouteTypeFilterPreference.fromValue(setOf("0", "garbage"))
        assertThat(parsed.value).containsExactly(RouteType.Train)
    }

    @Test
    fun `mapRouteTypeFilter fromValue all-unknown falls back to default`() {
        // Every entry was unparseable / unknown — set ends up empty after filtering, which
        // triggers the same fall-back as a null stored value.
        assertThat(MapRouteTypeFilterPreference.fromValue(setOf("99", "garbage")))
            .isEqualTo(MapRouteTypeFilterPreference.default)
    }

    @Test
    fun `mapRouteTypeFilter default contains the five visible modes`() {
        // Mirrors NearbyUiState.DEFAULT_FILTER exactly. Unknown is intentionally excluded — it's
        // a runtime fall-back, not a user-facing mode, so it never goes on the wire.
        assertThat(MapRouteTypeFilterPreference.default.value).containsExactly(
            RouteType.Train,
            RouteType.Tram,
            RouteType.Bus,
            RouteType.VLine,
            RouteType.NightBus,
        )
    }
}
