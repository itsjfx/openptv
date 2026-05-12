package ac.jfx.openptv.ui

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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ac.jfx.openptv.R
import ac.jfx.openptv.feature.search.SearchScreen
import ac.jfx.openptv.feature.settings.SettingsScreen
import ac.jfx.openptv.feature.setup.SetupScreen
import ac.jfx.openptv.ui.theme.LocalThemeMode
import ac.jfx.openptv.ui.theme.OpenPtvTheme
import ac.jfx.openptv.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Top-level navigation keys for the app. Each `data object` is a Navigation 3 destination key;
 * a later phase will move these into a dedicated `:core:navigation` module.
 */
sealed interface AppNavKey : NavKey {
    @Serializable
    data object Home : AppNavKey

    @Serializable
    data object Search : AppNavKey

    @Serializable
    data object Settings : AppNavKey
}

/**
 * Root composable. Owns the theme-mode state (in-memory for the barebones cut; DataStore lands
 * in Phase 04) and gates the main navigation behind the first-run setup flow.
 */
@Composable
fun App(appViewModel: AppViewModel = hiltViewModel()) {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    val gate by appViewModel.gate.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        OpenPtvTheme(themeMode = themeMode) {
            when (gate) {
                GateState.Loading -> SplashLoader()
                GateState.NeedsSetup -> SetupScreen(onSetupComplete = { /* gate flow flips */ })
                GateState.Ready -> MainNav(
                    themeMode = themeMode,
                    onCycleTheme = { themeMode = themeMode.next() },
                )
            }
        }
    }
}

@Composable
private fun MainNav(
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
) {
    val backStack = rememberNavBackStack(AppNavKey.Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<AppNavKey.Home> {
                HomeScreen(
                    themeMode = themeMode,
                    onCycleTheme = onCycleTheme,
                    onOpenSearch = { backStack.add(AppNavKey.Search) },
                    onOpenSettings = { backStack.add(AppNavKey.Settings) },
                )
            }
            entry<AppNavKey.Search> {
                SearchScreen()
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
            modifier = Modifier
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
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
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
                    text = stringResource(
                        R.string.theme_mode_label,
                        stringResource(themeMode.labelRes()),
                    ),
                )
            }
        }
    }
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
    ThemeMode.Dark -> ThemeMode.System
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.System -> R.string.theme_mode_system
    ThemeMode.Light -> R.string.theme_mode_light
    ThemeMode.Dark -> R.string.theme_mode_dark
}
