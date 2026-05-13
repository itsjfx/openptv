package ac.jfx.openptv.core.designsystem

import androidx.compose.runtime.compositionLocalOf

/**
 * Three-way theme mode. Persisted via DataStore in Phase 4; in-memory only for the barebones skeleton.
 */
enum class ThemeMode { System, Light, Dark }

/**
 * Composition local for the active theme mode. Screens read this to render a toggle UI.
 * The actual mutable state lives in the `App` composable for the barebones cut.
 */
val LocalThemeMode = compositionLocalOf { ThemeMode.System }
