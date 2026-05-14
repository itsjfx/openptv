package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.datastore.UserPreferences
import ac.jfx.openptv.core.datastore.UserPreferencesDataStore
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Test seam for `UserPreferencesDataStoreModule`. Same shape as `:feature:settings`'s test
 * module — kept module-local until a third feature needs the swap, at which point it gets
 * promoted to `:core:data-test`.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ac.jfx.openptv.core.datastore.UserPreferencesDataStoreModule::class],
)
internal object FakeUserPreferencesModule {
    @Provides
    @Singleton
    @UserPreferences
    fun provideUserPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("openptv_favourites_test") },
        )

    @Provides
    @Singleton
    fun provideUserPreferencesFacade(
        @UserPreferences dataStore: DataStore<Preferences>,
    ): UserPreferencesDataStore = UserPreferencesDataStore(dataStore)
}
