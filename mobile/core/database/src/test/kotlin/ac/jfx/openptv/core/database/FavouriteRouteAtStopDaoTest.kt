package ac.jfx.openptv.core.database

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.FavouriteRouteAtStopEntityMother.Companion.aFavouriteRouteAtStopEntity
import ac.jfx.openptv.core.database.dao.FavouriteRouteAtStopDao
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Unit tests for [FavouriteRouteAtStopDao] against an in-memory Room DB. Uses Robolectric so the
 * test runs on the JVM (gradle `:core:database:testDebugUnitTest`) without booting an emulator.
 *
 * The crown jewel is `observeAll_reemits_when_row_is_edited`: Room conflates
 * `Flow<List<...>>` by default, but an `@Upsert` that updates an *existing* row must still
 * trigger a downstream emission. A regression here would silently freeze the favourites screen
 * on stale data; `docs/mobile/phase-04-favourites.md` calls it out as the sneaky gotcha for v1.
 */
@RunWith(AndroidJUnit4::class)
// Pin the Robolectric SDK so the test doesn't try to load a system image for the project's
// `compileSdk = 36` (Robolectric 4.14.x ships SDK 34 jars; newer SDKs need a newer Robolectric).
// `manifest = NONE` skips manifest merging — this module has no XML to read anyway.
@Config(manifest = Config.NONE, sdk = [34])
class FavouriteRouteAtStopDaoTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteRouteAtStopDao

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        // `Dispatchers.setMain` lets Room's internal query coroutines run on the test dispatcher
        // instead of the real Android main thread (which isn't present under Robolectric here).
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, OpenPtvDatabase::class.java)
                // `allowMainThreadQueries` is fine in tests: it spares us a real executor for
                // the once-per-test setup path, and queries run via coroutines anyway.
                .allowMainThreadQueries()
                .build()
        dao = database.favouriteRouteAtStopDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsert_inserts_row_and_observeAll_emits_it() =
        runTest {
            val entity = aFavouriteRouteAtStopEntity().build()

            dao.upsert(entity)

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted).containsExactly(entity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAll_reemits_when_row_is_edited() =
        runTest {
            // The headline gotcha: Room conflates Flow emissions by default, so an upsert that
            // updates a row in-place (same primary key) must still trigger a downstream emit
            // because the *contents* changed. If Room ever silently dedupes on PK identity this
            // test fails — and the favourites list would freeze on stale display fields in prod.
            val initial =
                aFavouriteRouteAtStopEntity()
                    .withStopName("Old Name")
                    .build()
            dao.upsert(initial)

            dao.observeAll().test {
                val first = awaitItem()
                assertThat(first).hasSize(1)
                assertThat(first.first().stopName).isEqualTo("Old Name")

                // Same composite PK, different cached display fields — this is the scenario the
                // repository will hit when the user re-favourites after the stop's name
                // changes upstream (renames are rare, but still happen).
                val edited = initial.copy(stopName = "New Name", stopSuburb = "Different Suburb")
                dao.upsert(edited)

                val second = awaitItem()
                assertThat(second).hasSize(1)
                assertThat(second.first().stopName).isEqualTo("New Name")
                assertThat(second.first().stopSuburb).isEqualTo("Different Suburb")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun delete_removes_only_matching_composite_key() =
        runTest {
            val keep =
                aFavouriteRouteAtStopEntity()
                    .withStopId(1)
                    .withRouteId(10)
                    .withDirectionId(100)
                    .withPosition(0)
                    .build()
            val remove =
                aFavouriteRouteAtStopEntity()
                    .withStopId(2)
                    .withRouteId(20)
                    .withDirectionId(200)
                    .withPosition(1)
                    .build()
            val sameStopDifferentRoute =
                aFavouriteRouteAtStopEntity()
                    .withStopId(2)
                    .withRouteId(21)
                    .withDirectionId(200)
                    .withPosition(2)
                    .build()
            dao.upsert(keep)
            dao.upsert(remove)
            dao.upsert(sameStopDifferentRoute)

            dao.delete(stopId = 2, routeId = 20, directionId = 200)

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted).containsExactly(keep, sameStopDifferentRoute).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun reorder_updates_positions_and_observeAll_reflects_new_order() =
        runTest {
            val a =
                aFavouriteRouteAtStopEntity()
                    .withStopId(1)
                    .withRouteId(10)
                    .withDirectionId(100)
                    .withPosition(0)
                    .build()
            val b =
                aFavouriteRouteAtStopEntity()
                    .withStopId(2)
                    .withRouteId(20)
                    .withDirectionId(200)
                    .withPosition(1)
                    .build()
            val c =
                aFavouriteRouteAtStopEntity()
                    .withStopId(3)
                    .withRouteId(30)
                    .withDirectionId(300)
                    .withPosition(2)
                    .build()
            dao.upsert(a)
            dao.upsert(b)
            dao.upsert(c)

            // Reverse order: c, b, a → positions 0, 1, 2.
            dao.reorder(
                listOf(
                    Triple(3, 30, 300),
                    Triple(2, 20, 200),
                    Triple(1, 10, 100),
                ),
            )

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted.map { Triple(it.stopId, it.routeId, it.directionId) })
                    .containsExactly(
                        Triple(3, 30, 300),
                        Triple(2, 20, 200),
                        Triple(1, 10, 100),
                    ).inOrder()
                assertThat(emitted.map { it.position }).containsExactly(0, 1, 2).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
