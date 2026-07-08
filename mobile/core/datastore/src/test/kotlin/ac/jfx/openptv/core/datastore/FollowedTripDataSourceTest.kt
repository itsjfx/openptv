package ac.jfx.openptv.core.datastore

import ac.jfx.openptv.core.datastore.preference.PreferenceKeys
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.testing.FollowedTripMother
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip tests for [FollowedTripDataSource] against a real Preferences DataStore on a temp
 * file — same write-close-reopen template as [UserPreferencesDataStoreTest], so a broken wire
 * format can't hide behind an in-memory instance.
 */
class FollowedTripDataSourceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        prefsFile = File(tempFolder.newFolder("datastore"), "openptv_user_prefs.preferences_pb")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun newScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private fun openDataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })

    @Test
    fun empty_store_emits_null() =
        runTest {
            val scope = newScope()
            val source = FollowedTripDataSource(openDataStore(scope))

            source.followedTrip.test {
                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun followedTrip_persists_across_datastore_reopen() =
        runTest {
            val written =
                FollowedTripMother.aFollowedTrip()
                    .withRouteType(RouteType.VLine)
                    .withRouteLabel("Bairnsdale - Melbourne via Sale & Traralgon")
                    .build()

            val firstScope = newScope()
            val firstSource = FollowedTripDataSource(openDataStore(firstScope))
            firstSource.set(written)
            firstScope.cancel()

            val secondScope = newScope()
            val reopened = FollowedTripDataSource(openDataStore(secondScope))
            reopened.followedTrip.test {
                assertThat(awaitItem()).isEqualTo(written)
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }

    @Test
    fun nullable_fields_round_trip_as_null() =
        runTest {
            val written =
                FollowedTripMother.aFollowedTrip()
                    .withFromStopId(null)
                    .withRouteLabel(null)
                    .build()

            val firstScope = newScope()
            FollowedTripDataSource(openDataStore(firstScope)).set(written)
            firstScope.cancel()

            val secondScope = newScope()
            val reopened = FollowedTripDataSource(openDataStore(secondScope))
            reopened.followedTrip.test {
                val emitted = awaitItem()
                assertThat(emitted).isEqualTo(written)
                assertThat(emitted!!.fromStopId).isNull()
                assertThat(emitted.routeLabel).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }

    @Test
    fun set_replaces_the_previous_trip() =
        runTest {
            val scope = newScope()
            val source = FollowedTripDataSource(openDataStore(scope))
            val first = FollowedTripMother.aFollowedTrip().build()
            val second = FollowedTripMother.aFollowedTrip().withRunRef("111222").build()

            source.followedTrip.test {
                assertThat(awaitItem()).isNull()

                source.set(first)
                assertThat(awaitItem()).isEqualTo(first)

                source.set(second)
                assertThat(awaitItem()).isEqualTo(second)

                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun clear_removes_the_followed_trip() =
        runTest {
            val scope = newScope()
            val source = FollowedTripDataSource(openDataStore(scope))

            source.followedTrip.test {
                assertThat(awaitItem()).isNull()

                source.set(FollowedTripMother.aFollowedTrip().build())
                assertThat(awaitItem()).isNotNull()

                source.clear()
                assertThat(awaitItem()).isNull()

                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun malformed_payload_decodes_to_null_instead_of_crashing() =
        runTest {
            val scope = newScope()
            val store = openDataStore(scope)
            store.edit { prefs -> prefs[PreferenceKeys.FOLLOWED_TRIP] = "{not json at all" }

            val source = FollowedTripDataSource(store)
            source.followedTrip.test {
                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun payload_with_unknown_extra_fields_still_decodes() =
        runTest {
            // Forward-compat: a newer build (issue #201) may write extra fields.
            val scope = newScope()
            val store = openDataStore(scope)
            store.edit { prefs ->
                prefs[PreferenceKeys.FOLLOWED_TRIP] =
                    """
                    {
                      "run_ref": "953527",
                      "route_type": 0,
                      "destination_name": "Flinders Street",
                      "completes_at_utc": "2026-05-14T09:11:00Z",
                      "followed_at_utc": "2026-05-14T09:00:00Z",
                      "alight_stop_id": 1071
                    }
                    """.trimIndent()
            }

            val source = FollowedTripDataSource(store)
            source.followedTrip.test {
                val emitted = awaitItem()
                assertThat(emitted).isNotNull()
                assertThat(emitted!!.runRef.value).isEqualTo("953527")
                assertThat(emitted.routeType).isEqualTo(RouteType.Train)
                assertThat(emitted.fromStopId).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }
}
