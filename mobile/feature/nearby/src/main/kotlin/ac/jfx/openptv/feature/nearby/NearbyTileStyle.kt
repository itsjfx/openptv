package ac.jfx.openptv.feature.nearby

/**
 * Tile-style URL resolver for [OpenPtvMap]. Lives behind a single function so the swap from
 * OpenFreeMap to a self-hosted / paid alternative is a one-file change (see
 * `:feature:nearby/README.md` for the alternatives list).
 *
 * The values are intentionally constants, not BuildConfig fields — the OpenFreeMap project is
 * stable enough that pointing at it from source is fine, and a future swap should be a tracked
 * commit anyway.
 */
internal object NearbyTileStyle {
    /** OpenFreeMap light theme. Anonymous, no API key. */
    private const val POSITRON_STYLE_URL: String = "https://tiles.openfreemap.org/styles/positron"

    /** OpenFreeMap dark theme. Anonymous, no API key. */
    private const val DARK_STYLE_URL: String = "https://tiles.openfreemap.org/styles/dark"

    /**
     * Pick the style for the current theme. We don't read the theme from a composition local here
     * because the function lives in `:feature:nearby` and is called from inside the `AndroidView`
     * `factory =` block where Compose's locals haven't propagated yet — the caller resolves the
     * `isDark` boolean from its own composition scope and forwards it.
     */
    fun styleUrl(isDark: Boolean): String = if (isDark) DARK_STYLE_URL else POSITRON_STYLE_URL
}
