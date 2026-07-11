package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.datastore.FollowedTripDataSource
import ac.jfx.openptv.core.testing.FollowedTripMother
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
 * Tests for [FollowedTripRepositoryImpl] over a *real* [FollowedTripDataSource] backed by a
 * temp-file DataStore — real objects over fakes, per the testing conventions. The wire-format
 * edge cases live in `FollowedTripDataSourceTest` (`:core:datastore`); here we pin the
 * repository contract: observe, follow-replaces, unfollow, and restart survival.
 */
class FollowedTripRepositoryImplTest {
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

    private fun repository(scope: CoroutineScope): FollowedTripRepository =
        FollowedTripRepositoryImpl(FollowedTripDataSource(openDataStore(scope)))

    @Test
    fun `nothing followed emits null`() =
        runTest {
            val scope = newScope()
            repository(scope).followedTrip.test {
                assertThat(awaitItem()).isNull()
                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun `follow emits the trip and a second follow replaces it`() =
        runTest {
            val scope = newScope()
            val repository = repository(scope)
            val first = FollowedTripMother.aFollowedTrip().build()
            val replacement =
                FollowedTripMother.aFollowedTrip()
                    .withRunRef("111222")
                    .withDestinationName("Mernda")
                    .build()

            repository.followedTrip.test {
                assertThat(awaitItem()).isNull()

                repository.follow(first)
                assertThat(awaitItem()).isEqualTo(first)

                // One trip at a time: following another replaces, never accumulates.
                repository.follow(replacement)
                assertThat(awaitItem()).isEqualTo(replacement)

                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun `unfollow clears the trip and is a no-op when nothing is followed`() =
        runTest {
            val scope = newScope()
            val repository = repository(scope)

            // No-op unfollow on an empty store must not throw or emit garbage.
            repository.unfollow()

            repository.followedTrip.test {
                assertThat(awaitItem()).isNull()

                repository.follow(FollowedTripMother.aFollowedTrip().build())
                assertThat(awaitItem()).isNotNull()

                repository.unfollow()
                assertThat(awaitItem()).isNull()

                cancelAndIgnoreRemainingEvents()
            }
            scope.cancel()
        }

    @Test
    fun `followed trip survives a datastore reopen — the app-restart contract`() =
        runTest {
            val trip = FollowedTripMother.aFollowedTrip().build()

            val firstScope = newScope()
            repository(firstScope).follow(trip)
            firstScope.cancel()

            val secondScope = newScope()
            repository(secondScope).followedTrip.test {
                assertThat(awaitItem()).isEqualTo(trip)
                cancelAndIgnoreRemainingEvents()
            }
            secondScope.cancel()
        }
}
