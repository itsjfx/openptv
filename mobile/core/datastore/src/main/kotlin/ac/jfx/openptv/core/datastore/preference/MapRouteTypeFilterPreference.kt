package ac.jfx.openptv.core.datastore.preference

import ac.jfx.openptv.core.model.RouteType
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persisted route-type chip selection for the Nearby map (issue #112). Carries the same
 * `Set<RouteType>` that lives on `NearbyUiState.routeTypeFilter` so a "trams only" chip
 * configuration survives an app restart.
 *
 * **Wire format.** A `stringSetPreferencesKey` of stringified `RouteType.toCode()` integers —
 * `setOf("0", "1")` is `setOf(Train, Tram)`. Two reasons for the int wire instead of the enum
 * case name:
 *  1. PTV's `route_type` is the wire code already, so the int is the natural identifier.
 *  2. Renaming an enum case (e.g. `NightBus` → `NightNetwork`) doesn't silently lose users'
 *     persisted selections — the int is stable, the case name isn't.
 *
 * **Forward-compat.** `fromValue` drops unknown codes silently so an older build can still read
 * a newer build's file. If the resulting set is empty (every persisted code was unknown, or the
 * user somehow ended up with an empty set on disk) [fromValue] falls back to [default] to
 * preserve the "filter is never empty" invariant the ViewModel enforces at runtime.
 *
 * **Default.** Mirrors `NearbyUiState.DEFAULT_FILTER` — the five visible chips (Train, Tram,
 * Bus, V/Line, Night Bus). [RouteType.Unknown] is intentionally excluded: it's a runtime
 * fallback for unexpected wire codes, not a user-facing mode, and putting it on the wire would
 * just round-trip back to garbage.
 *
 * **One-way door.** The `MAP_ROUTE_TYPE_FILTER` key name + the int-string wire encoding are the
 * on-disk contract. Renaming the key or switching to enum-name strings without a DataStore
 * migration would silently lose every user's persisted selection at next launch.
 */
class MapRouteTypeFilterPreference private constructor(
    val value: Set<RouteType>,
) {
    /**
     * Persist this selection into [dataStore]. Fire-and-forget on the supplied [scope] so the UI
     * event handler returns immediately — matches the rest of the typed-preference DSL. Doesn't
     * extend [Preference] because that class is `Preference<T>` with a single typed payload;
     * a `Set<RouteType>` isn't a single enum case so the per-case `data object` pattern doesn't
     * fit. The shape — `value` + `put(scope, dataStore)` + companion `default` / `fromValue` —
     * mirrors the other preferences for consistency at the call site.
     */
    fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        // Encode every selected RouteType to its wire code as a string. `Unknown` is filtered
        // out defensively — the chip strip never lets it in, but a programmatic call could.
        val encoded =
            value
                .filter { it != RouteType.Unknown }
                .map { it.toCode().toString() }
                .toSet()
        scope.launch {
            dataStore.edit { prefs -> prefs[PreferenceKeys.MAP_ROUTE_TYPE_FILTER] = encoded }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is MapRouteTypeFilterPreference && value == other.value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "MapRouteTypeFilterPreference(value=$value)"

    companion object {
        /**
         * Default selection: every visible mode on. Matches `NearbyUiState.DEFAULT_FILTER`
         * verbatim so the seed an installer sees is exactly the seed the UI's chip strip starts
         * from on a fresh install.
         */
        val default: MapRouteTypeFilterPreference =
            MapRouteTypeFilterPreference(
                setOf(
                    RouteType.Train,
                    RouteType.Tram,
                    RouteType.Bus,
                    RouteType.VLine,
                    RouteType.NightBus,
                ),
            )

        /**
         * Reconstitute from the stored string set. Each entry is parsed as an int and mapped
         * through [RouteType.Companion.fromCode]; entries that aren't valid ints, or that map
         * to [RouteType.Unknown], are dropped silently (forward-compat: a newer build wrote a
         * code this build doesn't know).
         *
         * Falls back to [default] when:
         *  - `stored` is `null` (no value persisted yet — fresh install / first launch), or
         *  - the parsed set is empty (every entry was unknown / unparseable, OR a previous
         *    version somehow persisted an empty set). The "filter is never empty" invariant
         *    is the ViewModel's contract; honouring it at the parser keeps the seed honest.
         */
        fun fromValue(stored: Set<String>?): MapRouteTypeFilterPreference {
            if (stored == null) return default
            val parsed =
                stored
                    .mapNotNull { it.toIntOrNull() }
                    .map(RouteType.Companion::fromCode)
                    .filter { it != RouteType.Unknown }
                    .toSet()
            return if (parsed.isEmpty()) default else MapRouteTypeFilterPreference(parsed)
        }

        /** Wrap an arbitrary set without the parser's empty-set fallback. */
        fun of(value: Set<RouteType>): MapRouteTypeFilterPreference =
            MapRouteTypeFilterPreference(value)
    }
}
