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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.URLEncoder

/**
 * Stateful Hilt-aware entry point. The route owns the ViewModel handle, the composition-local
 * reads (theme mode + dynamic colour, seeded by `SettingsProvider` at the app root) and the
 * `serverConfigState` snapshot read off the [SettingsViewModel]. Server config is plumbed
 * through the ViewModel rather than a composition local because `SettingsRepository` predates
 * the typed preference DSL and exposes its own `Flow` directly — the row subtitle reads the
 * latest URL from the same flow the picker writes through, so a save inside the dialog reflects
 * on the row without an explicit refresh.
 */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val serverConfig by viewModel.serverConfigState.collectAsStateWithLifecycle(
        initialValue = ServerConfigState.empty,
    )
    SettingsScreen(
        themeMode = LocalThemeMode.current,
        dynamicColour = LocalDynamicColour.current,
        dynamicColourSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        timeFormat = LocalTimeFormat.current,
        serverConfig = serverConfig,
        defaultBackendUrl = viewModel.defaultBackendUrl,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColour = viewModel::setDynamicColour,
        onTimeFormat = viewModel::setTimeFormat,
        onServerSelectionSaved = viewModel::saveServerSelection,
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
 * And a Server section (added in #81, extended in #102 / PR #113 feedback) with a single
 * **Backend server** row showing either the currently-active proxy URL or a "Direct PTV API"
 * marker as its subtitle. Tapping opens a picker dialog with three radio choices — Default
 * proxy, Custom proxy, and Direct PTV API (user-supplied dev_id + key). The dialog writes
 * through [onServerSelectionSaved], which sequences the persists (direct flag + URL + creds)
 * so the row reflects the chosen mode on the next emit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeModePreference,
    dynamicColour: DynamicColourPreference,
    dynamicColourSupported: Boolean,
    timeFormat: TimeFormatPreference,
    serverConfig: ServerConfigState,
    defaultBackendUrl: String,
    onThemeMode: (ThemeModePreference) -> Unit,
    onDynamicColour: (DynamicColourPreference) -> Unit,
    onTimeFormat: (TimeFormatPreference) -> Unit,
    onServerSelectionSaved: (ServerPickerState) -> Unit,
) {
    var showServerDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        modifier = Modifier.testTag(TestTagRoot),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // Wrap in a vertical scroll so shorter devices can still reach every row
                    // once the dialog's TextField surfaces are open. Plain Column (not
                    // LazyColumn) is fine here: the row count is fixed and small, and
                    // LazyColumn's overhead would be wasted.
                    .verticalScroll(rememberScrollState()),
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
                serverConfig = serverConfig,
                onClick = { showServerDialog = true },
            )
        }
    }

    if (showServerDialog) {
        ServerPickerDialog(
            serverConfig = serverConfig,
            defaultUrl = defaultBackendUrl,
            onSave = { state ->
                onServerSelectionSaved(state)
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
 * Settings row showing the currently-active server connection. Plain `clickable` (not
 * `selectable` / `toggleable`) because tapping opens a dialog rather than committing a value —
 * TalkBack announces this as a button. The subtitle reflects the chosen mode: a "Direct PTV
 * API" marker when the user is signing on-device, otherwise the proxy URL.
 */
@Composable
private fun ServerRow(
    serverConfig: ServerConfigState,
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
            val subtitle =
                when {
                    serverConfig.directMode ->
                        stringResource(R.string.feature_settings_server_subtitle_direct)
                    serverConfig.backendUrl.isNotEmpty() -> serverConfig.backendUrl
                    else -> null
                }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Picker dialog. Three radio rows (Default / Custom / Direct PTV) plus conditional input
 * fields, delegated to [ServerPickerContent] so the first-run setup screen (`:app`'s
 * `SetupScreen`) renders the same picker inline.
 *
 * Initial state is computed inside `remember` keyed on [serverConfig] so re-opening the dialog
 * after a save reflects the just-saved value: if direct mode is on, the dialog opens on the
 * Direct PTV row with the persisted credentials. Otherwise, if the URL matches the bundled
 * default it opens on Default; else on Custom with the field pre-filled.
 */
@Composable
private fun ServerPickerDialog(
    serverConfig: ServerConfigState,
    defaultUrl: String,
    onSave: (ServerPickerState) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember(serverConfig, defaultUrl) {
        val initialChoice =
            when {
                serverConfig.directMode -> ServerChoice.DirectPtv
                serverConfig.backendUrl.isBlank() || serverConfig.backendUrl == defaultUrl ->
                    ServerChoice.Default
                else -> ServerChoice.Custom
            }
        val initialCustomUrl =
            if (initialChoice == ServerChoice.Custom) serverConfig.backendUrl else ""
        mutableStateOf(
            ServerPickerState(
                defaultUrl = defaultUrl,
                currentUrl = serverConfig.backendUrl,
                choice = initialChoice,
                customUrl = initialCustomUrl,
                devId = serverConfig.devId,
                apiKey = serverConfig.apiKey,
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
            // Dialog body can grow tall once the Direct PTV blurb + fields are visible, so
            // wrap in a vertical scroll. The radio rows themselves stay reachable on phones
            // shorter than the dialog's max content height.
            ServerPickerContent(
                state = state,
                onStateChange = { state = it },
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                // Always tappable. If the chosen option isn't committable we flip the
                // validation flag so the picker paints the offending fields red rather than
                // silently disabling Save — Material 3 required-field UX.
                onClick = {
                    if (state.canSave) {
                        onSave(state)
                    } else {
                        state = state.copy(showValidationErrors = true)
                    }
                },
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

/**
 * Three-radio server picker body, used by both the Settings dialog and the first-run setup
 * screen (`:app`'s `SetupScreen`). Stateless: the caller owns the [ServerPickerState] and
 * receives every keystroke / radio tap via [onStateChange].
 *
 * Layout is a plain [Column] — the caller picks the container (the Settings side wraps it
 * in a `verticalScroll` inside the dialog body; the setup screen drops it inline into the
 * scrollable column it already owns). The rows render in the same order on both surfaces —
 * Default, Custom (with conditional URL field), Direct PTV (with conditional credential fields
 * and helper text) — so the affordance reads identically wherever the picker shows up.
 *
 * Test tags (`TestTagServer*Choice`, `TestTagServerCustomUrlField`, `TestTagDirectMode*Field`)
 * are stable across surfaces, so feature tests that drove the dialog still drive the setup
 * screen unchanged.
 */
@Composable
fun ServerPickerContent(
    state: ServerPickerState,
    onStateChange: (ServerPickerState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        ServerChoiceRow(
            selected = state.choice == ServerChoice.Default,
            titleRes = R.string.feature_settings_server_default_title,
            bodyRes = R.string.feature_settings_server_default_body,
            detail = null,
            onClick = { onStateChange(state.copy(choice = ServerChoice.Default)) },
            testTag = TestTagServerDefaultChoice,
        )
        ServerChoiceRow(
            selected = state.choice == ServerChoice.Custom,
            titleRes = R.string.feature_settings_server_custom_title,
            bodyRes = R.string.feature_settings_server_custom_body,
            detail = null,
            onClick = { onStateChange(state.copy(choice = ServerChoice.Custom)) },
            testTag = TestTagServerCustomChoice,
        )
        if (state.choice == ServerChoice.Custom) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.customUrl,
                // Clear the validation flag on any keystroke so the field stops painting red
                // while the user is fixing it. Material 3 convention: error state is cleared
                // as soon as the user starts addressing it.
                onValueChange = {
                    onStateChange(state.copy(customUrl = it, showValidationErrors = false))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(TestTagServerCustomUrlField),
                label = {
                    RequiredFieldLabel(R.string.feature_settings_server_custom_field_label)
                },
                placeholder = { Text(stringResource(R.string.feature_settings_server_custom_field_placeholder)) },
                isError = state.customUrlError,
                supportingText = {
                    Text(
                        text =
                            if (state.customUrlError) {
                                stringResource(R.string.feature_settings_server_required_error)
                            } else {
                                stringResource(R.string.feature_settings_server_custom_field_helper)
                            },
                    )
                },
                singleLine = true,
            )
        }
        DirectPtvChoiceRow(
            selected = state.choice == ServerChoice.DirectPtv,
            onClick = { onStateChange(state.copy(choice = ServerChoice.DirectPtv)) },
        )
        if (state.choice == ServerChoice.DirectPtv) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.devId,
                onValueChange = {
                    onStateChange(state.copy(devId = it, showValidationErrors = false))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(TestTagDirectModeDevIdField),
                label = {
                    RequiredFieldLabel(R.string.feature_settings_server_direct_devid_label)
                },
                isError = state.devIdError,
                supportingText =
                    if (state.devIdError) {
                        { Text(stringResource(R.string.feature_settings_server_required_error)) }
                    } else {
                        null
                    },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = {
                    onStateChange(state.copy(apiKey = it, showValidationErrors = false))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(TestTagDirectModeApiKeyField),
                label = {
                    RequiredFieldLabel(R.string.feature_settings_server_direct_apikey_label)
                },
                isError = state.apiKeyError,
                supportingText =
                    if (state.apiKeyError) {
                        { Text(stringResource(R.string.feature_settings_server_required_error)) }
                    } else {
                        null
                    },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
            )
            Text(
                text = stringResource(R.string.feature_settings_server_direct_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
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

/**
 * Direct PTV radio row. Same affordance as [ServerChoiceRow] but the body is built as an
 * [AnnotatedString] so the email address and docs URL inside the copy render as tappable
 * [LinkAnnotation]s. The radio row itself stays a `selectable` so TalkBack still announces
 * it as a radio option — only the substring annotations carry separate click semantics, which
 * Compose routes through `LocalUriHandler` without touching the row's click.
 */
@Composable
private fun DirectPtvChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val body = ptvApiKeyBlurb()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton,
                )
                .testTag(TestTagServerDirectChoice)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.feature_settings_server_direct_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * `OutlinedTextField` label for a required field — appends a Material 3-styled "*" in the
 * error colour after the resolved label string. Inlined here rather than promoted to
 * `:core:designsystem` because it's only used by the three required text fields in this picker
 * and the Material spec for "required" is a single coloured asterisk, not a richer affordance.
 */
@Composable
private fun RequiredFieldLabel(stringRes: Int) {
    val label = stringResource(stringRes)
    Text(
        text =
            buildAnnotatedString {
                append(label)
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                    append(" *")
                }
            },
    )
}

/**
 * Build the Direct PTV blurb with two tappable spans. We rely on `indexOf` against the resolved
 * string rather than carrying span markers in `strings.xml`, because formatted-string spans in
 * Android resources don't survive translation tooling well and the source-of-truth substrings
 * (the email + the "see here" anchor) are stable per the string-resource comment.
 */
@Composable
private fun ptvApiKeyBlurb(): AnnotatedString {
    val body = stringResource(R.string.feature_settings_server_direct_body)
    val email = stringResource(R.string.feature_settings_ptv_email)
    val emailSubject = stringResource(R.string.feature_settings_ptv_email_subject)
    val emailBody = stringResource(R.string.feature_settings_ptv_email_body)
    val docsUrl = stringResource(R.string.feature_settings_ptv_docs_url)
    val docsAnchor = "see here"
    val mailto =
        "mailto:$email?subject=${URLEncoder.encode(emailSubject, Charsets.UTF_8.name())}" +
            "&body=${URLEncoder.encode(emailBody, Charsets.UTF_8.name())}"

    val linkStyle =
        TextLinkStyles(
            style =
                SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
        )

    return buildAnnotatedString {
        val emailIdx = body.indexOf(email)
        val docsIdx = body.indexOf(docsAnchor)

        // Two substrings can land in any order — append them in document order so the
        // surrounding `body` text reads exactly as written. Defence in depth: if either
        // substring is missing (translation accidentally dropped it), fall back to the raw
        // body without crashing.
        val anchors =
            listOfNotNull(
                if (emailIdx >= 0) emailIdx to (emailIdx + email.length to mailto) else null,
                if (docsIdx >= 0) docsIdx to (docsIdx + docsAnchor.length to docsUrl) else null,
            ).sortedBy { it.first }

        if (anchors.isEmpty()) {
            append(body)
            return@buildAnnotatedString
        }

        var cursor = 0
        for ((start, endAndUrl) in anchors) {
            val (end, url) = endAndUrl
            if (start > cursor) append(body.substring(cursor, start))
            withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) {
                append(body.substring(start, end))
            }
            cursor = end
        }
        if (cursor < body.length) append(body.substring(cursor))
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
internal const val TestTagServerDirectChoice: String = "settings-server-direct-choice"
internal const val TestTagServerCustomUrlField: String = "settings-server-custom-url-field"
internal const val TestTagServerDialogSave: String = "settings-server-dialog-save"
internal const val TestTagServerDialogCancel: String = "settings-server-dialog-cancel"
internal const val TestTagDirectModeDevIdField: String = "settings-direct-mode-devid"
internal const val TestTagDirectModeApiKeyField: String = "settings-direct-mode-apikey"
