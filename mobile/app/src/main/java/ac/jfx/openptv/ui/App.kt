package ac.jfx.openptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import ac.jfx.openptv.R
import ac.jfx.openptv.ui.theme.LocalThemeMode
import ac.jfx.openptv.ui.theme.OpenPtvTheme
import ac.jfx.openptv.ui.theme.ThemeMode
import kotlinx.serialization.Serializable

/**
 * Top-level navigation keys for the app. Each `data object` is a Navigation 3 destination key;
 * Phase 02 will move these into a dedicated `:core:navigation` module.
 */
sealed interface AppNavKey : NavKey {
    @Serializable
    data object Home : AppNavKey
}

/**
 * Root composable. Owns the theme-mode state (in-memory for the barebones cut; DataStore lands in Phase 4)
 * and the Navigation 3 back stack.
 */
@Composable
fun App() {
    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }

    CompositionLocalProvider(LocalThemeMode provides themeMode) {
        OpenPtvTheme(themeMode = themeMode) {
            val backStack = rememberNavBackStack(AppNavKey.Home)

            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider {
                    entry<AppNavKey.Home> {
                        HomeScreen(
                            themeMode = themeMode,
                            onCycleTheme = { themeMode = themeMode.next() },
                        )
                    }
                },
            )
        }
    }
}

/**
 * Placeholder Home destination. Renders a title, a one-line description, and a button that
 * cycles between System / Light / Dark theme modes to prove the theming wiring works end-to-end.
 */
@Composable
private fun HomeScreen(
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
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
            Button(onClick = onCycleTheme) {
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
