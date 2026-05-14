package ac.jfx.openptv.core.designsystem

/**
 * Three-way theme mode the designsystem renders against. Mirrored by
 * `ac.jfx.openptv.core.datastore.preference.ThemeModePreference.ThemeMode` — `:core:datastore`
 * owns the persisted preference DSL and exposes its own composition local; the designsystem
 * stays decoupled and consumes the value as a regular parameter.
 */
enum class ThemeMode { System, Light, Dark }
