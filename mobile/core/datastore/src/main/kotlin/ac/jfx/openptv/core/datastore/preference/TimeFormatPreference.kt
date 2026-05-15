package ac.jfx.openptv.core.datastore.preference

import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope

/**
 * Twelve / twenty-four hour clock for every absolute time the app renders (departure rows, "as
 * of" timestamp, the favourite row's scheduled/live clock-time). Relative phrases like
 * "in 4 min" are unaffected — they have no clock face.
 *
 * Default is [System] so a fresh install follows the OS-level 24-hour toggle (Settings →
 * System → Languages & input → Use 24-hour format on AOSP / GrapheneOS). Users who want a
 * specific clock face regardless of the system flip to [TwelveHour] / [TwentyFourHour] from
 * the Settings screen.
 *
 * The stored wire value is the case name (`"System"`, `"TwelveHour"`, `"TwentyFourHour"`).
 * Matches the pattern used by the other preferences in this module — see
 * [ThemeModePreference] for the rationale (reordering / inserting cases must not silently
 * swap users' settings).
 */
sealed class TimeFormatPreference : Preference<TimeFormatPreference.TimeFormat>() {
    enum class TimeFormat { System, TwelveHour, TwentyFourHour }

    data object System : TimeFormatPreference() {
        override val value: TimeFormat = TimeFormat.System
    }

    data object TwelveHour : TimeFormatPreference() {
        override val value: TimeFormat = TimeFormat.TwelveHour
    }

    data object TwentyFourHour : TimeFormatPreference() {
        override val value: TimeFormat = TimeFormat.TwentyFourHour
    }

    override fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        persist(scope, dataStore, PreferenceKeys.TIME_FORMAT, value.name)
    }

    companion object {
        /** The fallback the composition local resolves to when no `SettingsProvider` is in scope. */
        val default: TimeFormatPreference = System

        /**
         * Reconstitute from the stored wire string. Unknown / null falls back to [default] so a
         * forward-compatible addition (e.g. a future locale-aware case) doesn't crash older
         * builds reading the new value.
         */
        fun fromValue(stored: String?): TimeFormatPreference =
            when (stored) {
                TimeFormat.System.name -> System
                TimeFormat.TwelveHour.name -> TwelveHour
                TimeFormat.TwentyFourHour.name -> TwentyFourHour
                else -> default
            }
    }
}

/**
 * Composition local for the active time-format preference. Compose code reads
 * `LocalTimeFormat.current` and resolves it (with the system 24-hour flag) into a 12h/24h
 * clock-face string — see `:core:designsystem`'s `rememberFormatTimeOfDay` for the resolver.
 * The fallback ([TimeFormatPreference.default]) keeps previews and tests that do not install
 * a `SettingsProvider` rendering with the system-follow default.
 */
val LocalTimeFormat = compositionLocalOf { TimeFormatPreference.default }
