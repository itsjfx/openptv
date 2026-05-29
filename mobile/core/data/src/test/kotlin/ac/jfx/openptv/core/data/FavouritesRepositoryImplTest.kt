package ac.jfx.openptv.core.data

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.dao.FavouriteDestinationAtStopDao
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
 * it. Mirrors `:core:database`'s `FavouriteDestinationAtStopDaoTest` setup (Robolectric +
 * in-memory builder) so the JVM unit test runs without booting an emulator.
 *
 * Pinned via `@Config(sdk = [34])` because Robolectric 4.14.x ships SDK 34 jars; newer compileSdk
 * values don't have a matching Robolectric SDK image yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouritesRepositoryImplTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteDestinationAtStopDao
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
        dao = database.favouriteDestinationAtStopDao()
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
                    destinationKey = "north coburg",
                    routeType = RouteType.Tram,
                    stopName = "Flinders Street",
                    stopSuburb = "Melbourne City",
                    destinationName = "North Coburg",
                    lat = -37.8183,
                    lng = 144.9671,
                )

                val emitted = awaitItem()
                assertThat(emitted).hasSize(1)
                val only = emitted.single()
                assertThat(only.stopId).isEqualTo(StopId(STOP_ID))
                assertThat(only.destinationKey).isEqualTo("north coburg")
                assertThat(only.destinationName).isEqualTo("North Coburg")
                assertThat(only.addedAt).isEqualTo(fixedInstant)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `add assigns increasing position values to consecutive adds`() =
        runTest {
            repository.add(StopId(STOP_ID), "north coburg", RouteType.Tram, "A", "", "North Coburg", 0.0, 0.0)
            repository.add(StopId(STOP_ID), "city", RouteType.Tram, "A", "", "City", 0.0, 0.0)
            repository.add(StopId(STOP_ID + 1), "kew", RouteType.Bus, "B", "", "Kew", 0.0, 0.0)

            val all = repository.observe().first()
            assertThat(all.map { it.position }).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun `remove deletes only the matching composite key`() =
        runTest {
            repository.add(StopId(1), "north coburg", RouteType.Tram, "A", "", "North Coburg", 0.0, 0.0)
            repository.add(StopId(2), "city", RouteType.Train, "B", "", "City", 0.0, 0.0)
            repository.add(StopId(2), "frankston", RouteType.Train, "B", "", "Frankston", 0.0, 0.0)

            repository.remove(StopId(2), "city")

            val remaining = repository.observe().first()
            assertThat(remaining.map { it.stopId.value to it.destinationKey })
                .containsExactly(1 to "north coburg", 2 to "frankston")
        }

    @Test
    fun `reorder updates positions and observe reflects the new order`() =
        runTest {
            repository.add(StopId(1), "north coburg", RouteType.Tram, "A", "", "North Coburg", 0.0, 0.0)
            repository.add(StopId(2), "city", RouteType.Train, "B", "", "City", 0.0, 0.0)
            repository.add(StopId(3), "frankston", RouteType.Train, "C", "", "Frankston", 0.0, 0.0)

            // Reverse order.
            repository.reorder(
                listOf(
                    3 to "frankston",
                    2 to "city",
                    1 to "north coburg",
                ),
            )

            val reordered = repository.observe().first()
            assertThat(reordered.map { it.stopId.value to it.destinationKey })
                .containsExactly(
                    3 to "frankston",
                    2 to "city",
                    1 to "north coburg",
                ).inOrder()
            assertThat(reordered.map { it.position }).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun `isFavourite is reactive — false initially, true after add, false after remove`() =
        runTest {
            // Bumped Turbine timeout (default 3 s) because Room's `Flow` propagation through the
            // schema-tracked invalidation tracker is noticeably slower on the GHA `ubuntu-latest`
            // Robolectric runner than on a developer laptop. 10 s is generous.
            repository.isFavourite(StopId(STOP_ID), "north coburg")
                .test(timeout = 10.seconds) {
                    assertThat(awaitItem()).isFalse()

                    repository.add(
                        stopId = StopId(STOP_ID),
                        destinationKey = "north coburg",
                        routeType = RouteType.Tram,
                        stopName = "Flinders",
                        stopSuburb = "City",
                        destinationName = "North Coburg",
                        lat = 0.0,
                        lng = 0.0,
                    )
                    assertThat(awaitItem()).isTrue()

                    repository.remove(StopId(STOP_ID), "north coburg")
                    assertThat(awaitItem()).isFalse()

                    cancelAndIgnoreRemainingEvents()
                }
        }

    @Test
    fun `isFavourite distinguishes between different destinations at the same stop`() =
        runTest {
            repository.add(
                stopId = StopId(STOP_ID),
                destinationKey = "city",
                routeType = RouteType.Train,
                stopName = "Caulfield",
                stopSuburb = "Caulfield East",
                destinationName = "City",
                lat = 0.0,
                lng = 0.0,
            )

            val matching = repository.isFavourite(StopId(STOP_ID), "city").first()
            val different = repository.isFavourite(StopId(STOP_ID), "frankston").first()

            assertThat(matching).isTrue()
            assertThat(different).isFalse()
        }

    private companion object {
        const val STOP_ID = 1071
    }
}
