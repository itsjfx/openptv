package ac.jfx.openptv.feature.runpattern

/**
 * Tile-style URL resolver for the run-pattern map (issue #187). Mirrors `:feature:nearby`'s
 * `NearbyTileStyle` — the two are deliberately duplicated rather than shared, because the style
 * constants are a one-line concern and promoting them to a `:core` module just to dedupe two
 * strings would couple every map feature to a shared module for no real gain. If a third map
 * surface appears, promote then.
 *
 * Anonymous OpenFreeMap styles, no API key — the same source the nearby map uses, so tiles already
 * in the 50 MiB maps cache (warmed by nearby) are reused here.
 */
internal object RunPatternTileStyle {
    private const val POSITRON_STYLE_URL: String = "https://tiles.openfreemap.org/styles/positron"
    private const val DARK_STYLE_URL: String = "https://tiles.openfreemap.org/styles/dark"

    fun styleUrl(isDark: Boolean): String = if (isDark) DARK_STYLE_URL else POSITRON_STYLE_URL
}
