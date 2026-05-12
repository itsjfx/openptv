package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for [AppSettings]. The implementation lives in `:core:datastore` when the
 * multi-module split lands; for the barebones cut it's wired against DataStore Preferences in
 * `:app`. Callers see only this interface.
 */
interface SettingsRepository {
    /**
     * Current settings as a hot stream. Emits the latest persisted value on subscribe and on
     * every subsequent write. Used by the nav graph to decide whether to show the setup flow.
     */
    val settings: Flow<AppSettings>

    /** Update only the backend URL — used by the Settings screen post-onboarding. */
    suspend fun setBackendBaseUrl(url: String)

    /**
     * First-run completion: store the chosen URL and flip [AppSettings.setupCompleted] to true
     * in a single transaction so the nav graph never observes a half-finished setup.
     */
    suspend fun completeSetup(url: String)
}
