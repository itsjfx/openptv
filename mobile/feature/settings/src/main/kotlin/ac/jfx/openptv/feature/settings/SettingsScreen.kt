package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.LocalDynamicColour
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Stateful Hilt-aware entry point. The route owns nothing more than the ViewModel handle and
 * the composition-local reads that drive the screen — every visible piece of state lives in
 * the DataStore-backed locals seeded by `SettingsProvider` at the app root, so navigating in
 * here from any path renders against the persisted values immediately.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    SettingsScreen(
        themeMode = LocalThemeMode.current,
        dynamicColour = LocalDynamicColour.current,
        dynamicColourSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColour = viewModel::setDynamicColour,
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeModePreference,
    dynamicColour: DynamicColourPreference,
    dynamicColourSupported: Boolean,
    onThemeMode: (ThemeModePreference) -> Unit,
    onDynamicColour: (DynamicColourPreference) -> Unit,
    onBack: () -> Unit,
) {
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
        }
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

internal const val TestTagRoot: String = "settings-root"
internal const val TestTagThemeGroup: String = "settings-theme-group"
internal const val TestTagThemeSystem: String = "settings-theme-system"
internal const val TestTagThemeLight: String = "settings-theme-light"
internal const val TestTagThemeDark: String = "settings-theme-dark"
internal const val TestTagDynamicColourRow: String = "settings-dynamic-colour-row"
internal const val TestTagDynamicColourSwitch: String = "settings-dynamic-colour-switch"
