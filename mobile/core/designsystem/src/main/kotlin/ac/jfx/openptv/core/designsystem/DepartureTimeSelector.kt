package ac.jfx.openptv.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Shared "view departures around a chosen time" control (issue #182). Used by the stop-detail
 * header and the favourites page. Two visual states:
 *
 *  - **Live now** ([selectedTime] == null): an [AssistChip] reading "Departing now" that opens the
 *    date + time picker flow when tapped.
 *  - **Custom time** ([selectedTime] != null): an [InputChip] reading the chosen time (formatted
 *    via [formatTime], date-prefixed when not today) with a trailing ✕ that resets to live now via
 *    [onCleared]. Tapping the chip body re-opens the picker pre-filled with the current selection.
 *
 * The picker is a two-step Material 3 flow — [DatePickerDialog] then a [TimePicker] dialog — so
 * "tomorrow" / date rollover works, not just time-of-day (acceptance criterion). The control owns
 * its own dialog visibility state; the host only sees the resulting [Instant] via [onTimeSelected].
 *
 * Lives in `:core:designsystem` with a primitive seam (no `:core:common` / `:core:datastore`
 * dependency) so it stays the lowest common module both features can share. The caller folds in the
 * user's 12/24-hour preference by passing [use24Hour] and a [formatTime] lambda backed by their
 * `AbsoluteTimeFormatter`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureTimeSelector(
    selectedTime: Instant?,
    nowLabel: String,
    formatTime: (Instant) -> String,
    use24Hour: Boolean,
    onTimeSelected: (Instant) -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now(),
) {
    // Picker visibility: 0 = closed, 1 = date step, 2 = time step. A draft date is carried between
    // the two steps so the final Instant is assembled once the time is confirmed.
    var pickerStep by remember { mutableStateOf(0) }
    var draftDate by remember { mutableStateOf<LocalDate?>(null) }

    val anchor = (selectedTime ?: now).toLocalDateTime(timeZone)

    Surface(modifier = modifier) {
        if (selectedTime == null) {
            AssistChip(
                onClick = { pickerStep = 1 },
                label = { Text(nowLabel) },
                trailingIcon = { Text("▾", style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.testTag(TestTagTimeSelectorChip),
            )
        } else {
            val today = now.toLocalDateTime(timeZone).date
            val chosen = selectedTime.toLocalDateTime(timeZone)
            val label =
                if (chosen.date == today) {
                    formatTime(selectedTime)
                } else {
                    "${chosen.date.shortLabel()} ${formatTime(selectedTime)}"
                }
            InputChip(
                selected = true,
                onClick = { pickerStep = 1 },
                label = { Text(label) },
                trailingIcon = {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelLarge,
                        modifier =
                            Modifier
                                .testTag(TestTagTimeSelectorClear)
                                .clickable(onClick = onCleared)
                                .padding(horizontal = 4.dp),
                    )
                },
                modifier = Modifier.testTag(TestTagTimeSelectorChip),
            )
        }
    }

    if (pickerStep == 1) {
        val initialDate = (draftDate ?: anchor.date)
        val datePickerState =
            rememberDatePickerState(
                // Material 3's DatePicker keeps `selectedDateMillis` at UTC midnight of the chosen
                // calendar day, so the *initial* value must also be UTC midnight — passing a
                // local-zone midnight (which is the previous UTC day east of Greenwich) makes the
                // picker pre-select the wrong day and mis-map subsequent taps. Build it in UTC.
                initialSelectedDateMillis = initialDate.atUtcMidnightMillis(),
            )
        DatePickerDialog(
            onDismissRequest = { pickerStep = 0 },
            confirmButton = {
                Button(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis
                        draftDate =
                            if (millis != null) {
                                Instant.fromEpochMilliseconds(millis)
                                    // The date picker reports midnight UTC for the chosen day; read
                                    // its date back in UTC so the calendar day matches what the user
                                    // tapped regardless of the device time zone.
                                    .toLocalDateTime(TimeZone.UTC).date
                            } else {
                                anchor.date
                            }
                        pickerStep = 2
                    },
                    modifier = Modifier.testTag(TestTagDateConfirm),
                ) {
                    Text("Next")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerStep = 0 }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (pickerStep == 2) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = anchor.hour,
                initialMinute = anchor.minute,
                is24Hour = use24Hour,
            )
        DatePickerDialog(
            onDismissRequest = { pickerStep = 0 },
            confirmButton = {
                Button(
                    onClick = {
                        val date = draftDate ?: anchor.date
                        val dateTime =
                            LocalDateTime(
                                date = date,
                                time = LocalTime(hour = timePickerState.hour, minute = timePickerState.minute),
                            )
                        onTimeSelected(dateTime.toInstant(timeZone))
                        draftDate = null
                        pickerStep = 0
                    },
                    modifier = Modifier.testTag(TestTagTimeConfirm),
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerStep = 1 }) { Text("Back") }
            },
        ) {
            TimePicker(
                state = timePickerState,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/** UTC-midnight epoch millis for this calendar day — the unit Material 3's DatePicker speaks. */
private fun LocalDate.atUtcMidnightMillis(): Long =
    LocalDateTime(this, LocalTime(hour = 0, minute = 0)).toInstant(TimeZone.UTC).toEpochMilliseconds()

/** "Sat 21 Jun" — compact date prefix for a non-today custom time. */
private fun LocalDate.shortLabel(): String {
    val day = dayOfWeek.name.take(THREE).lowercase().replaceFirstChar { it.uppercase() }
    val mon = month.name.take(THREE).lowercase().replaceFirstChar { it.uppercase() }
    return "$day $dayOfMonth $mon"
}

private const val THREE = 3

internal const val TestTagTimeSelectorChip: String = "departure-time-selector-chip"
internal const val TestTagTimeSelectorClear: String = "departure-time-selector-clear"
internal const val TestTagDateConfirm: String = "departure-time-selector-date-confirm"
internal const val TestTagTimeConfirm: String = "departure-time-selector-time-confirm"
