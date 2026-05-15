package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.BuildConfig
import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.model.AppSettings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-Preferences-backed [SettingsRepository]. Stores the user's chosen backend URL
 * and the setup-completed flag.
 *
 * Defaults:
 * - `backendBaseUrl` falls back to [BuildConfig.BACKEND_BASE_URL] when nothing is stored
 *   (debug builds prefill the emulator loopback, release prefills the hosted placeholder).
 *   That value is only used as a seed for the setup screen — no requests fire until the user
 *   accepts.
 * - `setupCompleted` defaults to `false` so the first launch always shows the setup flow.
 */
@Singleton
internal class SettingsRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : SettingsRepository {
        override val settings: Flow<AppSettings> =
            dataStore.data.map { prefs ->
                AppSettings(
                    backendBaseUrl = prefs[KEY_BACKEND_BASE_URL] ?: BuildConfig.BACKEND_BASE_URL,
                    setupCompleted = prefs[KEY_SETUP_COMPLETED] == true,
                )
            }

        override val defaultBackendBaseUrl: String = BuildConfig.BACKEND_BASE_URL

        override suspend fun setBackendBaseUrl(url: String) {
            dataStore.edit { prefs -> prefs[KEY_BACKEND_BASE_URL] = url.normalised() }
        }

        override suspend fun completeSetup(url: String) {
            dataStore.edit { prefs ->
                prefs[KEY_BACKEND_BASE_URL] = url.normalised()
                prefs[KEY_SETUP_COMPLETED] = true
            }
        }

        private companion object {
            val KEY_BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
            val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        }
    }

/**
 * Retrofit treats a base URL without a trailing slash as relative to the parent path. Normalise
 * once here so every consumer is dealing with a well-formed value regardless of how the user
 * typed it on the setup screen.
 */
private fun String.normalised(): String = trim().let { if (it.endsWith('/')) it else "$it/" }
