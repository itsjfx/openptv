package ac.jfx.openptv.core.datastore.preference

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The root of the typed Preference DSL. Each user-facing setting is modelled as a sealed
 * subclass with one `data object` per possible value, so:
 *
 *  - Compose code reads `LocalThemeMode.current` and exhaustively pattern-matches on the cases
 *    instead of comparing magic enum ordinals.
 *  - The producer side (`put(scope)`) is a one-liner; callers don't need to know the underlying
 *    [Preferences.Key] or the on-wire stored string.
 *
 * The pattern is borrowed from ReadYou (`me.ash.reader.infrastructure.preference.Preference`,
 * Apache 2.0). The shape kept here is the smallest one that satisfies the requirements in
 * `docs/mobile/phase-04-favourites.md`:
 *
 *  1. `value: T` — the *useful* payload (the enum case, the boolean, the typed sort order).
 *  2. `put(scope, dataStore)` — fire-and-forget write that launches on the supplied scope.
 *     A `scope` parameter (rather than a `suspend fun`) matches the call site we expect — a
 *     UI event handler that should not block the composition thread.
 *  3. Each subclass owns a `Preferences.Key<*>` and a `fromValue` companion that knows how to
 *     reconstitute the typed instance from the stored wire value. `fromValue` MUST tolerate
 *     `null` (no stored value yet) and unknown strings (forward-compat) by returning `default`.
 *
 * The class is `sealed` so the DSL gains exhaustive `when` matching at every call site — useful
 * for `SettingsProvider` and for the future `:feature:settings` screen, both of which iterate
 * over every possible case for picker UI.
 */
sealed class Preference<T> {
    /** The current typed payload — Compose-friendly, no string parsing required. */
    abstract val value: T

    /**
     * Persist this preference into [dataStore]. The write runs on [scope] so the caller (a UI
     * event handler) returns immediately. DataStore is already serialised on its internal
     * dispatcher, so two near-simultaneous `put` calls land in order without explicit locking.
     */
    abstract fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    )

    /**
     * Internal helper subclasses use to push a single value via `edit { ... }`. Keeps the
     * `launch + edit` boilerplate in one place so every subclass `put` is one line.
     */
    protected fun <V> persist(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
        key: Preferences.Key<V>,
        value: V,
    ) {
        scope.launch {
            dataStore.edit { prefs -> prefs[key] = value }
        }
    }
}
