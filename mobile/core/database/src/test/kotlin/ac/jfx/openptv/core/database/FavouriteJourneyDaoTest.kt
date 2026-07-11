package ac.jfx.openptv.core.database

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.FavouriteJourneyEntityMother.Companion.aFavouriteJourneyEntity
import ac.jfx.openptv.core.database.dao.FavouriteJourneyDao
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
 * Unit tests for [FavouriteJourneyDao] (issue #209) against an in-memory Room DB — Robolectric
 * on the JVM, same harness as [FavouriteDestinationAtStopDaoTest].
 *
 * The direction test matters most: A→B and B→A share the same two stop ids but are distinct
 * rows because the primary key is the *ordered* pair. A regression there would silently merge
 * "to work" and "home again" into one favourite.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouriteJourneyDaoTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteJourneyDao

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, OpenPtvDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.favouriteJourneyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsert_inserts_row_and_observeAll_emits_it() =
        runTest {
            val entity = aFavouriteJourneyEntity().build()

            dao.upsert(entity)

            dao.observeAll().test {
                assertThat(awaitItem()).containsExactly(entity)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun observeAll_orders_by_addedAt_ascending() =
        runTest {
            val newer =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1030)
                    .withAddedAt(2_000L)
                    .build()
            val older =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1162)
                    .withAddedAt(1_000L)
                    .build()
            dao.upsert(newer)
            dao.upsert(older)

            dao.observeAll().test {
                assertThat(awaitItem()).containsExactly(older, newer).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun reversed_pair_is_a_distinct_row() =
        runTest {
            // A→B and B→A share the same two stop ids; the ordered primary key must keep both.
            val outbound =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1162)
                    .withDestinationStopId(1071)
                    .withAddedAt(1_000L)
                    .build()
            val inbound =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1071)
                    .withDestinationStopId(1162)
                    .withAddedAt(2_000L)
                    .build()

            dao.upsert(outbound)
            dao.upsert(inbound)

            dao.observeAll().test {
                assertThat(awaitItem()).containsExactly(outbound, inbound).inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun upsert_same_pair_replaces_the_row() =
        runTest {
            val original = aFavouriteJourneyEntity().withOriginStopName("Old Name").build()
            dao.upsert(original)

            dao.upsert(original.copy(originStopName = "New Name", addedAt = 2_000L))

            dao.observeAll().test {
                val emitted = awaitItem()
                assertThat(emitted).hasSize(1)
                assertThat(emitted.single().originStopName).isEqualTo("New Name")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun delete_removes_only_the_matching_ordered_pair() =
        runTest {
            val outbound =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1162)
                    .withDestinationStopId(1071)
                    .withAddedAt(1_000L)
                    .build()
            val inbound =
                aFavouriteJourneyEntity()
                    .withOriginStopId(1071)
                    .withDestinationStopId(1162)
                    .withAddedAt(2_000L)
                    .build()
            dao.upsert(outbound)
            dao.upsert(inbound)

            dao.delete(originStopId = 1162, destinationStopId = 1071)

            dao.observeAll().test {
                assertThat(awaitItem()).containsExactly(inbound)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
