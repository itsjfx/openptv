package ac.jfx.openptv.core.datastore.preference

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Resolve [LocalTimeFormat] into a boolean `use24Hour` for the [ac.jfx.openptv.core.common.AbsoluteTimeFormatter].
 *
 * - [TimeFormatPreference.TwelveHour] → `false`.
 * - [TimeFormatPreference.TwentyFourHour] → `true`.
 * - [TimeFormatPreference.System] → whatever `android.text.format.DateFormat.is24HourFormat`
 *   reports for the current context.
 *
 * Lives in `:core:datastore` (not `:core:common`) so the Compose-bound `LocalContext` /
 * `LocalConfiguration` reads stay alongside the typed preference DSL. Re-evaluated when the
 * configuration changes (e.g. the user flips the system 24-hour toggle while the app is open)
 * because [LocalConfiguration] is the key — Compose recomposes the consumer.
 */
@Composable
fun rememberUse24Hour(timeFormat: TimeFormatPreference = LocalTimeFormat.current): Boolean {
    // Key the `remember` on configuration so Compose re-resolves the system flag when the
    // user changes the device 24-hour toggle. `LocalConfiguration.current` returns a new
    // `Configuration` instance on every relevant config change.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(timeFormat, configuration) {
        when (timeFormat) {
            TimeFormatPreference.TwelveHour -> false
            TimeFormatPreference.TwentyFourHour -> true
            TimeFormatPreference.System -> DateFormat.is24HourFormat(context)
        }
    }
}
