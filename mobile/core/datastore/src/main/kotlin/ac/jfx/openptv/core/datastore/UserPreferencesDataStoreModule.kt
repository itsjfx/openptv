package ac.jfx.openptv.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Tags the user-preferences `DataStore<Preferences>` so Hilt can disambiguate it from the
 * existing app-settings `DataStore` provided by `:app`'s `DataStoreModule` (which backs
 * `SettingsRepositoryImpl` for setup-state / backend-URL).
 *
 * Two separate stores rather than one shared one is deliberate:
 *  - The `:app` store survives `cc @itsjfx` so removing it would touch the setup flow; this
 *    issue specifically scopes the new module to *user* preferences (theme, dynamic colour,
 *    favourites sort) per `docs/mobile/phase-04-favourites.md`.
 *  - Splitting the on-disk files limits blast radius — corruption in one file leaves the other
 *    untouched, and `:app`'s `setupCompleted` flag is the gate that controls whether the
 *    network layer can fire at all, so keeping it physically separated from per-feature
 *    preferences is the safer default.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserPreferences

/**
 * Hilt module wiring the user-preferences `DataStore`. The store is a `@Singleton` so the
 * underlying coroutine scope (with its `SupervisorJob`) lives for the application lifetime —
 * a process-wide singleton is what the AndroidX `preferencesDataStore` delegate gives you
 * implicitly; we open-code it here so the [UserPreferences] qualifier is the only knob.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object UserPreferencesDataStoreModule {
    private const val PREFS_NAME = "openptv_user_prefs"

    @Provides
    @Singleton
    @UserPreferences
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(PREFS_NAME) },
        )

    @Provides
    @Singleton
    fun provideUserPreferencesFacade(
        @UserPreferences dataStore: DataStore<Preferences>,
    ): UserPreferencesDataStore = UserPreferencesDataStore(dataStore)
}
