package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.LocalDynamicColour
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.datastore.preference.LocalTimeFormat
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.datastore.preference.TimeFormatPreference
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful Hilt-aware entry point. The route owns the ViewModel handle, the composition-local
 * reads (theme mode + dynamic colour, seeded by `SettingsProvider` at the app root) and the
 * `currentBackendUrl` state read off the [SettingsViewModel]. Server URL is plumbed through
 * the ViewModel rather than a composition local because `SettingsRepository` predates the typed
 * preference DSL and exposes its own `Flow` directly — the row subtitle reads the latest URL
 * from the same flow the picker writes through, so a save inside the dialog reflects on the
 * row without an explicit refresh.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val currentBackendUrl by viewModel.currentBackendUrl.collectAsStateWithLifecycle(
        initialValue = "",
    )
    val directModeState by viewModel.directModeState.collectAsStateWithLifecycle(
        initialValue = DirectModeState.empty,
    )
    SettingsScreen(
        themeMode = LocalThemeMode.current,
        dynamicColour = LocalDynamicColour.current,
        dynamicColourSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        timeFormat = LocalTimeFormat.current,
        currentBackendUrl = currentBackendUrl,
        defaultBackendUrl = viewModel.defaultBackendUrl,
        directModeState = directModeState,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColour = viewModel::setDynamicColour,
        onTimeFormat = viewModel::setTimeFormat,
        onBackendUrl = viewModel::setBackendBaseUrl,
        onDirectMode = viewModel::setDirectMode,
        onDevId = viewModel::setDevId,
        onApiKey = viewModel::setApiKey,
        onBack = onBack,
    )
}

/**
 * Stateless [SettingsScreen]. Renders one Appearance section with:
 *
 *  - **Theme mode** — a three-row radio group (System / Light / Dark). The selected row matches
 *    [themeMode]; tapping any row fires [onThemeMode] with the typed preference and the row
 *    flips on the next composition pass once the local re-emits from DataStore.
 *  - **Dynamic colour** — a switch row. On Android 12+ it reflects [dynamicColour.value] and
 *    fires [onDynamicColour] with the inverted preference when toggled. On Android 11 and
 *    below the row is disabled and shows "Available on Android 12+" as a subtitle — kept
 *    visible (not hidden) so users get an explanation rather than a missing affordance.
 *
 * And a Server section (added in #81) with:
 *
 *  - **Backend server** — a row showing the currently-active URL as its subtitle. Tapping
 *    opens a picker dialog mirroring the first-run setup choices (default vs custom URL); the
 *    same validation rule (`effectiveUrl.isNotBlank()`) gates the Save button. The dialog
 *    writes through [onBackendUrl] which delegates to `SettingsRepository.setBackendBaseUrl`,
 *    so URL normalisation (trim + trailing slash) stays in one place across both surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeModePreference,
    dynamicColour: DynamicColourPreference,
    dynamicColourSupported: Boolean,
    timeFormat: TimeFormatPreference,
    currentBackendUrl: String,
    defaultBackendUrl: String,
    directModeState: DirectModeState,
    onThemeMode: (ThemeModePreference) -> Unit,
    onDynamicColour: (DynamicColourPreference) -> Unit,
    onTimeFormat: (TimeFormatPreference) -> Unit,
    onBackendUrl: (String) -> Unit,
    onDirectMode: (Boolean) -> Unit,
    onDevId: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onBack: () -> Unit,
) {
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // Material icons aren't pulled into this module to keep the dep
                        // surface tight; a glyph stand-in keeps the back affordance.
                        // Mirrors `:feature:stop-detail`'s `StopDetailScreen.kt:184`.
                        Text(
                            text = "‹",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        modifier = Modifier.testTag(TestTagRoot),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            SectionHeader(text = stringResource(R.string.feature_settings_appearance_section))
            ThemeModeSection(
                selected = themeMode,
                onSelect = onThemeMode,
            )
            DynamicColourSection(
                value = dynamicColour,
                supported = dynamicColourSupported,
                onToggle = onDynamicColour,
            )

            SectionHeader(text = stringResource(R.string.feature_settings_time_format_section))
            TimeFormatSection(
                selected = timeFormat,
                onSelect = onTimeFormat,
            )

            SectionHeader(text = stringResource(R.string.feature_settings_server_section))
            ServerRow(
                currentUrl = currentBackendUrl,
                onClick = { showServerDialog = true },
            )
            DirectModeSection(
                state = directModeState,
                onToggle = onDirectMode,
                onDevId = onDevId,
                onApiKey = onApiKey,
            )
        }
    }

    if (showServerDialog) {
        ServerPickerDialog(
            currentUrl = currentBackendUrl,
            defaultUrl = defaultBackendUrl,
            onSave = { url ->
                onBackendUrl(url)
                showServerDialog = false
            },
            onDismiss = { showServerDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun ThemeModeSection(
    selected: ThemeModePreference,
    onSelect: (ThemeModePreference) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup().testTag(TestTagThemeGroup),
    ) {
        ThemeModeRow(
            label = stringResource(R.string.feature_settings_theme_mode_system),
            preference = ThemeModePreference.System,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagThemeSystem,
        )
        ThemeModeRow(
            label = stringResource(R.string.feature_settings_theme_mode_light),
            preference = ThemeModePreference.Light,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagThemeLight,
        )
        ThemeModeRow(
            label = stringResource(R.string.feature_settings_theme_mode_dark),
            preference = ThemeModePreference.Dark,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagThemeDark,
        )
    }
}

@Composable
private fun ThemeModeRow(
    label: String,
    preference: ThemeModePreference,
    selected: ThemeModePreference,
    onSelect: (ThemeModePreference) -> Unit,
    testTag: String,
) {
    val isSelected = preference == selected
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // `selectable` (not `clickable`) so TalkBack announces the row as a radio
                // option inside the enclosing `selectableGroup`. The `Role.RadioButton` hint
                // matches the leading affordance.
                .selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = { onSelect(preference) },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            // `null` here so the `selectable` row owns the click; tapping the radio itself
            // still fires through the parent. Without this you'd get a double `onSelect`
            // via TalkBack's per-control activation.
            onClick = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun TimeFormatSection(
    selected: TimeFormatPreference,
    onSelect: (TimeFormatPreference) -> Unit,
) {
    Column(
        modifier = Modifier.selectableGroup().testTag(TestTagTimeFormatGroup),
    ) {
        TimeFormatRow(
            label = stringResource(R.string.feature_settings_time_format_system),
            preference = TimeFormatPreference.System,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagTimeFormatSystem,
        )
        TimeFormatRow(
            label = stringResource(R.string.feature_settings_time_format_twelve),
            preference = TimeFormatPreference.TwelveHour,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagTimeFormatTwelve,
        )
        TimeFormatRow(
            label = stringResource(R.string.feature_settings_time_format_twenty_four),
            preference = TimeFormatPreference.TwentyFourHour,
            selected = selected,
            onSelect = onSelect,
            testTag = TestTagTimeFormatTwentyFour,
        )
    }
}

@Composable
private fun TimeFormatRow(
    label: String,
    preference: TimeFormatPreference,
    selected: TimeFormatPreference,
    onSelect: (TimeFormatPreference) -> Unit,
    testTag: String,
) {
    val isSelected = preference == selected
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // `selectable` so TalkBack announces this as a radio option inside the
                // enclosing `selectableGroup`. Same shape as `ThemeModeRow` above.
                .selectable(
                    selected = isSelected,
                    role = Role.RadioButton,
                    onClick = { onSelect(preference) },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun DynamicColourSection(
    value: DynamicColourPreference,
    supported: Boolean,
    onToggle: (DynamicColourPreference) -> Unit,
) {
    val isOn = value.value && supported
    val subtitleRes =
        if (supported) {
            R.string.feature_settings_dynamic_colour_supported_subtitle
        } else {
            R.string.feature_settings_dynamic_colour_unsupported_subtitle
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (supported) {
                        // `toggleable` so TalkBack announces the row as a switch and the
                        // entire row is the tap target. Disabled on pre-12 to match the
                        // disabled Switch — keeps the affordance honest.
                        Modifier.toggleable(
                            value = isOn,
                            role = Role.Switch,
                            onValueChange = { newValue ->
                                onToggle(
                                    if (newValue) {
                                        DynamicColourPreference.On
                                    } else {
                                        DynamicColourPreference.Off
                                    },
                                )
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag(TestTagDynamicColourRow),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(
                text = stringResource(R.string.feature_settings_dynamic_colour_label),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (supported) {
                        LocalContentColor.current
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isOn,
            // `null` so the row's `toggleable` owns the change; matches the RadioRow pattern.
            // When the row isn't toggleable (pre-12), pass a no-op so the switch stays
            // visually present but inert.
            onCheckedChange = null,
            enabled = supported,
            modifier = Modifier.testTag(TestTagDynamicColourSwitch),
        )
    }
}

/**
 * Settings row showing the currently-active backend URL. Plain `clickable` (not `selectable` /
 * `toggleable`) because tapping opens a dialog rather than committing a value — TalkBack
 * announces this as a button.
 */
@Composable
private fun ServerRow(
    currentUrl: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag(TestTagServerRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.feature_settings_server_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            // The active URL appears as the subtitle so users can see what they're hitting
            // without opening the dialog. Empty for one frame on first composition while the
            // ViewModel's StateFlow seeds — kept blank rather than showing a placeholder so a
            // restart-persistence smoke test reads cleanly.
            if (currentUrl.isNotEmpty()) {
                Text(
                    text = currentUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Direct-PTV-mode section. The toggle row mirrors `DynamicColourSection`'s shape (full-row
 * `toggleable` + `Switch` with `onCheckedChange = null`), and the two TextFields appear inline
 * below the toggle when it's on. Persistence is write-on-each-change — the user's last
 * keystroke is what gets saved, no explicit Save button. The dev_id field is plain text; the
 * api_key field is masked via [PasswordVisualTransformation] so over-the-shoulder readers can't
 * see the secret. Auto-cap is off + `KeyboardCapitalization.None` because both values are
 * case-sensitive opaque tokens — the system's "capitalize first letter" default would silently
 * mangle them on first paste.
 */
@Composable
private fun DirectModeSection(
    state: DirectModeState,
    onToggle: (Boolean) -> Unit,
    onDevId: (String) -> Unit,
    onApiKey: (String) -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.enabled,
                        role = Role.Switch,
                        onValueChange = onToggle,
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .testTag(TestTagDirectModeRow),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.feature_settings_direct_mode_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.feature_settings_direct_mode_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.enabled,
                // `null` so the row's `toggleable` owns the change; matches the dynamic-colour row.
                onCheckedChange = null,
                modifier = Modifier.testTag(TestTagDirectModeSwitch),
            )
        }
        if (state.enabled) {
            OutlinedTextField(
                value = state.devId,
                onValueChange = onDevId,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag(TestTagDirectModeDevIdField),
                label = { Text(stringResource(R.string.feature_settings_direct_mode_devid_label)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKey,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag(TestTagDirectModeApiKeyField),
                label = { Text(stringResource(R.string.feature_settings_direct_mode_apikey_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
            )
            Text(
                text = stringResource(R.string.feature_settings_direct_mode_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Picker dialog. Two radio rows (Default / Custom) plus a conditional URL field. The `Save`
 * button is disabled until [ServerPickerState.canSave] is `true` — same validation rule the
 * onboarding `SetupUiState.canContinue` uses (minus consent, which only applies on first run).
 *
 * Initial state is computed inside `remember` keyed on [currentUrl] so re-opening the dialog
 * after a save reflects the just-saved value: if it matches the default URL the dialog opens
 * on the Default row; otherwise it opens on Custom with the field pre-filled with the user's
 * last URL.
 */
@Composable
private fun ServerPickerDialog(
    currentUrl: String,
    defaultUrl: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember(currentUrl, defaultUrl) {
        // If the persisted URL matches the bundled default, open on Default with an empty
        // custom field. Otherwise open on Custom with the field pre-filled — re-opening the
        // dialog after a custom save shouldn't blow away what the user previously typed.
        val isOnDefault = currentUrl.isBlank() || currentUrl == defaultUrl
        mutableStateOf(
            ServerPickerState(
                defaultUrl = defaultUrl,
                currentUrl = currentUrl,
                choice = if (isOnDefault) ServerChoice.Default else ServerChoice.Custom,
                customUrl = if (isOnDefault) "" else currentUrl,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTagServerDialog),
        // `dismissOnClickOutside = false` intentionally — Compose 2026.04-alpha's
        // `AlertDialog` `text` slot fires the outside-click dismissal even when the tap lands
        // inside the dialog window if there's an interactive composable (`clickable` / `selectable`)
        // in the slot. The dialog still dismisses on Cancel, Save, or system back. Re-evaluate
        // when the BOM moves past the regression.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.feature_settings_server_dialog_title)) },
        text = {
            Column {
                ServerChoiceRow(
                    selected = state.choice == ServerChoice.Default,
                    titleRes = R.string.feature_settings_server_default_title,
                    bodyRes = R.string.feature_settings_server_default_body,
                    detail = state.defaultUrl,
                    onClick = { state = state.copy(choice = ServerChoice.Default) },
                    testTag = TestTagServerDefaultChoice,
                )
                ServerChoiceRow(
                    selected = state.choice == ServerChoice.Custom,
                    titleRes = R.string.feature_settings_server_custom_title,
                    bodyRes = R.string.feature_settings_server_custom_body,
                    detail = null,
                    onClick = { state = state.copy(choice = ServerChoice.Custom) },
                    testTag = TestTagServerCustomChoice,
                )
                if (state.choice == ServerChoice.Custom) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.customUrl,
                        onValueChange = { state = state.copy(customUrl = it) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TestTagServerCustomUrlField),
                        label = { Text(stringResource(R.string.feature_settings_server_custom_field_label)) },
                        placeholder = { Text(stringResource(R.string.feature_settings_server_custom_field_placeholder)) },
                        supportingText = { Text(stringResource(R.string.feature_settings_server_custom_field_helper)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(state.effectiveUrl) },
                enabled = state.canSave,
                modifier = Modifier.testTag(TestTagServerDialogSave),
            ) {
                Text(stringResource(R.string.feature_settings_server_dialog_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TestTagServerDialogCancel),
            ) {
                Text(stringResource(R.string.feature_settings_server_dialog_cancel))
            }
        },
    )
}

@Composable
private fun ServerChoiceRow(
    selected: Boolean,
    titleRes: Int,
    bodyRes: Int,
    detail: String?,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // `selectable` (not `clickable`) so TalkBack announces the row as a radio
                // option inside the enclosing `Column`. Mirrors the ThemeRow pattern in this
                // file and the onboarding `ServerChoiceRow` in `:app`.
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton,
                )
                .testTag(testTag)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

internal const val TestTagRoot: String = "settings-root"
internal const val TestTagThemeGroup: String = "settings-theme-group"
internal const val TestTagThemeSystem: String = "settings-theme-system"
internal const val TestTagThemeLight: String = "settings-theme-light"
internal const val TestTagThemeDark: String = "settings-theme-dark"
internal const val TestTagDynamicColourRow: String = "settings-dynamic-colour-row"
internal const val TestTagDynamicColourSwitch: String = "settings-dynamic-colour-switch"
internal const val TestTagTimeFormatGroup: String = "settings-time-format-group"
internal const val TestTagTimeFormatSystem: String = "settings-time-format-system"
internal const val TestTagTimeFormatTwelve: String = "settings-time-format-twelve"
internal const val TestTagTimeFormatTwentyFour: String = "settings-time-format-twenty-four"
internal const val TestTagServerRow: String = "settings-server-row"
internal const val TestTagServerDialog: String = "settings-server-dialog"
internal const val TestTagServerDefaultChoice: String = "settings-server-default-choice"
internal const val TestTagServerCustomChoice: String = "settings-server-custom-choice"
internal const val TestTagServerCustomUrlField: String = "settings-server-custom-url-field"
internal const val TestTagServerDialogSave: String = "settings-server-dialog-save"
internal const val TestTagServerDialogCancel: String = "settings-server-dialog-cancel"
internal const val TestTagDirectModeRow: String = "settings-direct-mode-row"
internal const val TestTagDirectModeSwitch: String = "settings-direct-mode-switch"
internal const val TestTagDirectModeDevIdField: String = "settings-direct-mode-devid"
internal const val TestTagDirectModeApiKeyField: String = "settings-direct-mode-apikey"
