package ac.jfx.openptv.core.data

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.dao.FavouriteRouteAtStopDao
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [FavouritesRepositoryImpl] against a **real** in-memory Room DB + DAO. Per the
 * project's testing priority order (`CLAUDE.md`), the DAO is the seam under test — we do not mock
 * it. Mirrors `:core:database`'s `FavouriteRouteAtStopDaoTest` setup (Robolectric + in-memory
 * builder) so the JVM unit test runs without booting an emulator.
 *
 * Pinned via `@Config(sdk = [34])` because Robolectric 4.14.x ships SDK 34 jars; newer compileSdk
 * values don't have a matching Robolectric SDK image yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouritesRepositoryImplTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteRouteAtStopDao
    private lateinit var repository: FavouritesRepositoryImpl

    private val fixedInstant: Instant = Instant.parse("2026-05-14T09:00:00Z")
    private val fixedClock: Clock =
        object : Clock {
            override fun now(): Instant = fixedInstant
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, OpenPtvDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.favouriteRouteAtStopDao()
        repository = FavouritesRepositoryImpl(dao = dao, clock = fixedClock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `add inserts the favourite and observe re-emits with it`() =
        runTest {
            repository.observe().test {
                assertThat(awaitItem()).isEmpty()

                repository.add(
                    stopId = StopId(STOP_ID),
                    routeType = RouteType.Tram,
                    routeId = RouteId(ROUTE_ID),
                    directionId = DirectionId(DIRECTION_ID),
                    stopName = "Flinders Street",
                    stopSuburb = "Melbourne City",
                    routeNumber = "19",
                    routeName = "North Coburg",
                    directionName = "North Coburg",
                    lat = -37.8183,
                    lng = 144.9671,
                )

                val emitted = awaitItem()
                assertThat(emitted).hasSize(1)
                val only = emitted.single()
                assertThat(only.stopId).isEqualTo(StopId(STOP_ID))
                assertThat(only.routeId).isEqualTo(RouteId(ROUTE_ID))
                assertThat(only.directionId).isEqualTo(DirectionId(DIRECTION_ID))
                assertThat(only.routeNumber).isEqualTo("19")
                assertThat(only.addedAt).isEqualTo(fixedInstant)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `add assigns increasing position values to consecutive adds`() =
        runTest {
            repository.add(StopId(STOP_ID), RouteType.Tram, RouteId(ROUTE_ID), DirectionId(1), "A", "", "1", "", "", 0.0, 0.0)
            repository.add(StopId(STOP_ID), RouteType.Tram, RouteId(ROUTE_ID), DirectionId(2), "A", "", "1", "", "", 0.0, 0.0)
            repository.add(StopId(STOP_ID + 1), RouteType.Bus, RouteId(ROUTE_ID + 1), DirectionId(1), "B", "", "2", "", "", 0.0, 0.0)

            val all = repository.observe().first()
            assertThat(all.map { it.position }).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun `remove deletes only the matching composite key`() =
        runTest {
            repository.add(StopId(1), RouteType.Tram, RouteId(10), DirectionId(100), "A", "", "1", "", "", 0.0, 0.0)
            repository.add(StopId(2), RouteType.Tram, RouteId(20), DirectionId(200), "B", "", "2", "", "", 0.0, 0.0)
            repository.add(StopId(2), RouteType.Tram, RouteId(21), DirectionId(200), "B", "", "21", "", "", 0.0, 0.0)

            repository.remove(StopId(2), RouteId(20), DirectionId(200))

            val remaining = repository.observe().first()
            assertThat(remaining.map { Triple(it.stopId.value, it.routeId.value, it.directionId.value) })
                .containsExactly(Triple(1, 10, 100), Triple(2, 21, 200))
        }

    @Test
    fun `reorder updates positions and observe reflects the new order`() =
        runTest {
            repository.add(StopId(1), RouteType.Tram, RouteId(10), DirectionId(100), "A", "", "1", "", "", 0.0, 0.0)
            repository.add(StopId(2), RouteType.Tram, RouteId(20), DirectionId(200), "B", "", "2", "", "", 0.0, 0.0)
            repository.add(StopId(3), RouteType.Tram, RouteId(30), DirectionId(300), "C", "", "3", "", "", 0.0, 0.0)

            // Reverse order: C, B, A → positions 0, 1, 2.
            repository.reorder(
                listOf(
                    Triple(3, 30, 300),
                    Triple(2, 20, 200),
                    Triple(1, 10, 100),
                ),
            )

            val reordered = repository.observe().first()
            assertThat(reordered.map { Triple(it.stopId.value, it.routeId.value, it.directionId.value) })
                .containsExactly(
                    Triple(3, 30, 300),
                    Triple(2, 20, 200),
                    Triple(1, 10, 100),
                ).inOrder()
            assertThat(reordered.map { it.position }).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun `isFavourite is reactive — false initially, true after add, false after remove`() =
        runTest {
            // Bumped Turbine timeout (default is 3 s) because Room's `Flow` propagation through
            // the schema-tracked invalidation tracker is noticeably slower on the GHA `ubuntu-
            // latest` Robolectric runner than on a developer laptop. 10 s is generous; the test
            // typically completes in well under 1 s locally.
            repository.isFavourite(StopId(STOP_ID), RouteId(ROUTE_ID), DirectionId(DIRECTION_ID))
                .test(timeout = 10.seconds) {
                    assertThat(awaitItem()).isFalse()

                    repository.add(
                        stopId = StopId(STOP_ID),
                        routeType = RouteType.Tram,
                        routeId = RouteId(ROUTE_ID),
                        directionId = DirectionId(DIRECTION_ID),
                        stopName = "Flinders",
                        stopSuburb = "City",
                        routeNumber = "19",
                        routeName = "North Coburg",
                        directionName = "North Coburg",
                        lat = 0.0,
                        lng = 0.0,
                    )
                    assertThat(awaitItem()).isTrue()

                    repository.remove(StopId(STOP_ID), RouteId(ROUTE_ID), DirectionId(DIRECTION_ID))
                    assertThat(awaitItem()).isFalse()

                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun `isFavourite distinguishes between different composite keys at the same stop`() =
        runTest {
            // Same stop, different (routeId, directionId). The favourites unit is the triple, so
            // one direction being favourited does not flip the other's `isFavourite` to true.
            repository.add(
                stopId = StopId(STOP_ID),
                routeType = RouteType.Tram,
                routeId = RouteId(ROUTE_ID),
                directionId = DirectionId(1),
                stopName = "A",
                stopSuburb = "",
                routeNumber = "19",
                routeName = "",
                directionName = "",
                lat = 0.0,
                lng = 0.0,
            )

            val matching = repository.isFavourite(StopId(STOP_ID), RouteId(ROUTE_ID), DirectionId(1)).first()
            val differentDirection =
                repository.isFavourite(StopId(STOP_ID), RouteId(ROUTE_ID), DirectionId(2)).first()
            val differentRoute =
                repository.isFavourite(StopId(STOP_ID), RouteId(ROUTE_ID + 1), DirectionId(1)).first()

            assertThat(matching).isTrue()
            assertThat(differentDirection).isFalse()
            assertThat(differentRoute).isFalse()
        }

    private companion object {
        const val STOP_ID = 1071
        const val ROUTE_ID = 1881
        const val DIRECTION_ID = 9
    }
}
