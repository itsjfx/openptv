package ac.jfx.openptv

import ac.jfx.openptv.core.datastore.SettingsProvider
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import ac.jfx.openptv.core.datastore.preference.LocalThemeMode
import ac.jfx.openptv.core.designsystem.OpenPtvTheme
import ac.jfx.openptv.ui.App
import ac.jfx.openptv.ui.toDesignSystem
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-Activity Compose host. All UI lives inside [App]; this class exists only to
 * bridge the platform Activity lifecycle into Compose and to satisfy Hilt's entry-point contract.
 *
 * Wraps the content in [SettingsProvider] so every user preference (theme mode, dynamic colour,
 * favourites sort) is collected once at the root and pushed down through composition locals.
 * Per the Phase 4 spec, the only screen that previously held theme state (`App.kt`'s
 * `rememberSaveable { ThemeMode.System }`) is migrated to read `LocalThemeMode.current` from
 * `:core:datastore` — the `remember`-based switcher is gone.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var userPreferences: UserPreferencesDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SettingsProvider(userPreferences = userPreferences) {
                OpenPtvTheme(themeMode = LocalThemeMode.current.value.toDesignSystem()) {
                    App()
                }
            }
        }
    }
}
