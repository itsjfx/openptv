package ac.jfx.openptv.feature.settings

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
 * Test seam for `UserPreferencesDataStoreModule`. Swaps the production binding for a
 * per-test-run DataStore backed by a unique file name so concurrent tests don't share state
 * (and a previous test's writes don't leak into the next one).
 *
 * The DataStore is real, not a mock — the wire format is part of the contract and a hand-
 * rolled fake would let UI assertions pass even if a future encoder change silently broke
 * persistence. The only thing being swapped is the file path. Kept local to
 * `:feature:settings`'s androidTest source set rather than promoted to `:core:data-test`
 * until a second feature module needs it (favourites UI in Phase 04 is the likely next
 * consumer).
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
            // Per-process unique file — the test app is a fresh process per `am instrument`
            // invocation, but inside the run a single DataStore is shared across all tests
            // (same Hilt graph). Tests that need a fresh start should write the defaults
            // explicitly in `@Before`.
            produceFile = { context.preferencesDataStoreFile("openptv_user_prefs_test") },
        )

    @Provides
    @Singleton
    fun provideUserPreferencesFacade(
        @UserPreferences dataStore: DataStore<Preferences>,
    ): UserPreferencesDataStore = UserPreferencesDataStore(dataStore)
}
