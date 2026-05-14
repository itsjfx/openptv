package ac.jfx.openptv.ui

import ac.jfx.openptv.R
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.designsystem.ThemeMode
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.navigation.AppNavKey
import ac.jfx.openptv.feature.search.SearchScreen
import ac.jfx.openptv.feature.settings.SettingsScreen
import ac.jfx.openptv.feature.setup.SetupScreen
import ac.jfx.openptv.feature.stopdetail.StopDetailRoute
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope

/**
 * Root composable. The theme mode is read from `LocalThemeMode.current` (provided by
 * `SettingsProvider` at `MainActivity`'s `setContent`) and written back via the typed
 * [ThemeModePreference] DSL — the previous `rememberSaveable { ThemeMode.System }` switcher
 * is gone now that `:core:datastore` owns persisted preferences.
 *
 * Setup gating still goes through the existing `:app`-owned `SettingsRepository` (backend URL
 * + `setupCompleted` flag) — that's a separate concern from user preferences.
 */
@Composable
fun App(appViewModel: AppViewModel = hiltViewModel()) {
    val gate by appViewModel.gate.collectAsStateWithLifecycle()
    val themePreference = LocalThemeMode.current
    val scope = rememberCoroutineScope()
    val userPreferences = appViewModel.userPreferences

    when (gate) {
        GateState.Loading -> SplashLoader()
        GateState.NeedsSetup -> SetupScreen(onSetupComplete = { /* gate flow flips */ })
        GateState.Ready ->
            MainNav(
                themeMode = themePreference.value,
                onCycleTheme = { cycle(themePreference, scope, userPreferences) },
            )
    }
}

private fun cycle(
    current: ThemeModePreference,
    scope: CoroutineScope,
    userPreferences: UserPreferencesDataStore,
) {
    val next =
        when (current) {
            ThemeModePreference.System -> ThemeModePreference.Light
            ThemeModePreference.Light -> ThemeModePreference.Dark
            ThemeModePreference.Dark -> ThemeModePreference.System
        }
    next.put(scope, userPreferences.dataStore)
}

@Composable
private fun MainNav(
    themeMode: ThemeModePreference.ThemeMode,
    onCycleTheme: () -> Unit,
) {
    val backStack = rememberNavBackStack(AppNavKey.Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
            entryProvider {
                entry<AppNavKey.Home> {
                    HomeScreen(
                        themeMode = themeMode,
                        onCycleTheme = onCycleTheme,
                        onOpenSearch = { backStack.add(AppNavKey.Search) },
                        onOpenSettings = { backStack.add(AppNavKey.Settings) },
                    )
                }
                entry<AppNavKey.Search> {
                    SearchScreen(
                        onStopSelected = { stop ->
                            // Phase 03: replace the search-screen snackbar with real navigation
                            // into stop detail. The screen now snackbar's on its own only as a
                            // visual confirmation; the real action is the back-stack push.
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stop.id.value,
                                    routeTypeCode = stop.routeType.toCode(),
                                ),
                            )
                        },
                    )
                }
                entry<AppNavKey.StopDetail> { key ->
                    StopDetailRoute(
                        stopId = StopId(key.stopId),
                        routeType = RouteType.fromCode(key.routeTypeCode),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppNavKey.Settings> {
                    SettingsScreen(onBack = { backStack.removeLastOrNull() })
                }
            },
    )
}

@Composable
private fun SplashLoader() {
    Scaffold { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Placeholder Home destination. Renders a title, a one-line description, a theme-cycle button,
 * a "Search stops" button, and a Settings entry point.
 */
@Composable
private fun HomeScreen(
    themeMode: ThemeModePreference.ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            Button(onClick = onOpenSearch) {
                Text(text = stringResource(R.string.home_open_search))
            }
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(text = stringResource(R.string.home_open_settings))
            }
            Button(
                onClick = onCycleTheme,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.theme_mode_label,
                            stringResource(themeMode.labelRes()),
                        ),
                )
            }
        }
    }
}

private fun ThemeModePreference.ThemeMode.labelRes(): Int =
    when (this) {
        ThemeModePreference.ThemeMode.System -> R.string.theme_mode_system
        ThemeModePreference.ThemeMode.Light -> R.string.theme_mode_light
        ThemeModePreference.ThemeMode.Dark -> R.string.theme_mode_dark
    }

/**
 * Maps the `:core:datastore` user-preference theme enum to the `:core:designsystem`
 * theme enum. The two are intentionally kept separate so the designsystem doesn't depend on
 * datastore — this single hop lives in `:app`'s composition root.
 */
internal fun ThemeModePreference.ThemeMode.toDesignSystem(): ThemeMode =
    when (this) {
        ThemeModePreference.ThemeMode.System -> ThemeMode.System
        ThemeModePreference.ThemeMode.Light -> ThemeMode.Light
        ThemeModePreference.ThemeMode.Dark -> ThemeMode.Dark
    }
