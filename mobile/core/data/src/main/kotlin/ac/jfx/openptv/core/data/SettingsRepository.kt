package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for [AppSettings]. The DataStore-backed implementation lives in `:app` for the
 * barebones cut — `:core:datastore` lands in the phase that needs typed preference DSL. Callers
 * see only this interface so the eventual relocation is a one-line dep change.
 */
interface SettingsRepository {
    /**
     * Current settings as a hot stream. Emits the latest persisted value on subscribe and on
     * every subsequent write. Used by the nav graph to decide whether to show the setup flow.
     */
    val settings: Flow<AppSettings>

    /**
     * The bundled default backend base URL — `BuildConfig.BACKEND_BASE_URL` for the
     * DataStore-backed impl. Exposed on the interface so feature modules (e.g.
     * `:feature:settings`'s server picker) can show "what's the default" as the subtitle of the
     * Default radio row without depending on `:app`'s `BuildConfig`. The picker also uses this
     * to decide whether the currently-persisted URL matches the default (open the dialog on
     * the Default row) or is custom (open on Custom with the field pre-filled).
     */
    val defaultBackendBaseUrl: String

    /** Update only the backend URL — used by the Settings screen post-onboarding. */
    suspend fun setBackendBaseUrl(url: String)

    /**
     * Direct-mode toggle. When `true`, the network layer signs requests locally with the user's
     * [AppSettings.devId] / [AppSettings.apiKey] and calls PTV's host directly, bypassing the
     * proxy URL. Flipping the flag takes effect on the next request — no rebuild of the
     * Retrofit graph.
     */
    suspend fun setDirectMode(enabled: Boolean)

    /** Update the user's PTV `devid` (used for signing in direct mode). */
    suspend fun setDevId(devId: String)

    /** Update the user's PTV API key (used for signing in direct mode). */
    suspend fun setApiKey(apiKey: String)

    /**
     * First-run completion: store the chosen URL and flip [AppSettings.setupCompleted] to true
     * in a single transaction so the nav graph never observes a half-finished setup.
     */
    suspend fun completeSetup(url: String)
}
