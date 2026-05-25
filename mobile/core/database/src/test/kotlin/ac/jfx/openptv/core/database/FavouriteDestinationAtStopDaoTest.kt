package ac.jfx.openptv.core.database

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.FavouriteDestinationAtStopEntityMother.Companion.aFavouriteDestinationAtStopEntity
import ac.jfx.openptv.core.database.dao.FavouriteDestinationAtStopDao
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
 * Unit tests for [FavouriteDestinationAtStopDao] against an in-memory Room DB. Uses Robolectric so
 * the test runs on the JVM (gradle `:core:database:testDebugUnitTest`) without booting an emulator.
 *
 * The crown jewel is `observeAll_reemits_when_row_is_edited`: Room conflates `Flow<List<...>>`
 * by default, but an `@Upsert` that updates an *existing* row must still trigger a downstream
 * emission. A regression here would silently freeze the favourites screen on stale data.
 */
@RunWith(AndroidJUnit4::class)
// Pin the Robolectric SDK so the test doesn't try to load a system image for the project's
// `compileSdk`. `manifest = NONE` skips manifest merging — this module has no XML to read.
@Config(manifest = Config.NONE, sdk = [34])
class FavouriteDestinationAtStopDaoTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteDestinationAtStopDao

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, OpenPtvDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.favouriteDestinationAtStopDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsert_inserts_row_and_observeAll_emits_it() =
        runTest {
            val entity = aFavouriteDestinationAtStopEntity().build()

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
                aFavouriteDestinationAtStopEntity()
                    .withStopName("Old Name")
                    .build()
            dao.upsert(initial)

            dao.observeAll().test {
                val first = awaitItem()
                assertThat(first).hasSize(1)
                assertThat(first.first().stopName).isEqualTo("Old Name")

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
                aFavouriteDestinationAtStopEntity()
                    .withStopId(1)
                    .withDestinationKey("north coburg")
                    .withPosition(0)
                    .build()
            val remove =
                aFavouriteDestinationAtStopEntity()
                    .withStopId(2)
                    .withDestinationKey("city")
                    .withPosition(1)
                    .build()
            val sameStopDifferentDestination =
                aFavouriteDestinationAtStopEntity()
                    .withStopId(2)
                    .withDestinationKey("cranbourne")
                    .withPosition(2)
                    .build()
            dao.upsert(keep)
            dao.upsert(remove)
            dao.upsert(sameStopDifferentDestination)

            dao.delete(stopId = 2, destinationKey = "city")

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted).containsExactly(keep, sameStopDifferentDestination).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun reorder_updates_positions_and_observeAll_reflects_new_order() =
        runTest {
            val a =
                aFavouriteDestinationAtStopEntity()
                    .withStopId(1)
                    .withDestinationKey("north coburg")
                    .withPosition(0)
                    .build()
            val b =
                aFavouriteDestinationAtStopEntity()
                    .withStopId(2)
                    .withDestinationKey("city")
                    .withPosition(1)
                    .build()
            val c =
                aFavouriteDestinationAtStopEntity()
                    .withStopId(3)
                    .withDestinationKey("frankston")
                    .withPosition(2)
                    .build()
            dao.upsert(a)
            dao.upsert(b)
            dao.upsert(c)

            // Reverse order: c, b, a → positions 0, 1, 2.
            dao.reorder(
                listOf(
                    3 to "frankston",
                    2 to "city",
                    1 to "north coburg",
                ),
            )

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted.map { it.stopId to it.destinationKey })
                    .containsExactly(
                        3 to "frankston",
                        2 to "city",
                        1 to "north coburg",
                    ).inOrder()
                assertThat(emitted.map { it.position }).containsExactly(0, 1, 2).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
