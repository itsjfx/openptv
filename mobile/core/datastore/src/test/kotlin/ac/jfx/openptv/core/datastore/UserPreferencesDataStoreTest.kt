package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.DynamicColourPreference
import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Persistence round-trip tests for the typed `Preference` DSL backed by a real Preferences
 * DataStore on a temp file. Mirrors `:core:database`'s in-memory-Room approach: keep the test
 * surface honest by using the actual storage backend, not a hand-rolled fake.
 *
 * For each preference:
 *  1. Open store A, write a non-default value via `put(scope, dataStore)`, drain.
 *  2. Cancel store A's scope (DataStore disallows two active stores against the same file —
 *     it throws `IllegalStateException` from `OkioStorage.createConnection`).
 *  3. Open store B against the same file, read back through `UserPreferencesDataStore` — the
 *     typed flow must emit the value we wrote, not the companion `default`.
 *
 * The cancel-then-reopen step is the load-bearing part. A test that wrote and read back from
 * the same in-memory instance would pass even if the wire format was broken; only re-opening
 * proves the value made it onto disk and the `fromValue` decoder agrees with the `put` encoder.
 */
class UserPreferencesDataStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        prefsFile = File(tempFolder.newFolder("datastore"), "openptv_user_prefs.preferences_pb")
    }

    /**
     * Fresh `CoroutineScope` per DataStore instance. DataStore enforces at most one live
     * instance per file (via its internal scope-cancellation registry); a new scope for each
     * open call lets us cancel the previous scope, releasing the file lock before the next
     * `openDataStore()` call.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun newScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private fun openDataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })

    @Test
    fun themeMode_persists_across_datastore_reopen() =
        runTest {
            val firstScope = newScope()
            val firstStore = openDataStore(firstScope)
            ThemeModePreference.Dark.put(firstScope, firstStore)
            // Drain: DataStore's actor is serialised, so `data.first()` does not return until our
            // queued `put` write has been applied.
            firstStore.data.first()
            firstScope.cancel()

            val secondScope = newScope()
            val reopened = UserPreferencesDataStore(openDataStore(secondScope))
            reopened.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Dark)
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }

    @Test
    fun dynamicColour_persists_across_datastore_reopen() =
        runTest {
            val firstScope = newScope()
            val firstStore = openDataStore(firstScope)
            DynamicColourPreference.Off.put(firstScope, firstStore)
            firstStore.data.first()
            firstScope.cancel()

            val secondScope = newScope()
            val reopened = UserPreferencesDataStore(openDataStore(secondScope))
            reopened.dynamicColour.test {
                assertThat(awaitItem()).isEqualTo(DynamicColourPreference.Off)
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }

    @Test
    fun favouritesSort_persists_across_datastore_reopen() =
        runTest {
            val firstScope = newScope()
            val firstStore = openDataStore(firstScope)
            FavouritesSortPreference.Alphabetical.put(firstScope, firstStore)
            firstStore.data.first()
            firstScope.cancel()

            val secondScope = newScope()
            val reopened = UserPreferencesDataStore(openDataStore(secondScope))
            reopened.favouritesSort.test {
                assertThat(awaitItem()).isEqualTo(FavouritesSortPreference.Alphabetical)
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }

    @Test
    fun empty_datastore_emits_default_for_every_preference() =
        runTest {
            val scope = newScope()
            val facade = UserPreferencesDataStore(openDataStore(scope))

            facade.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)
                cancelAndIgnoreRemainingEvents()
            }
            facade.dynamicColour.test {
                assertThat(awaitItem()).isEqualTo(DynamicColourPreference.default)
                cancelAndIgnoreRemainingEvents()
            }
            facade.favouritesSort.test {
                assertThat(awaitItem()).isEqualTo(FavouritesSortPreference.default)
                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun multiple_writes_re_emit_through_typed_flow() =
        runTest {
            val scope = newScope()
            val store = openDataStore(scope)
            val facade = UserPreferencesDataStore(store)

            facade.themeMode.test {
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.default)

                ThemeModePreference.Light.put(scope, store)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Light)

                ThemeModePreference.Dark.put(scope, store)
                assertThat(awaitItem()).isEqualTo(ThemeModePreference.Dark)

                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }
}
