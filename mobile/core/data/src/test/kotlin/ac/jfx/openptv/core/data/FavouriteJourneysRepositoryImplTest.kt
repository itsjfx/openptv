package ac.jfx.openptv.core.data

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.dao.FavouriteJourneyDao
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.StopMother
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

/**
 * Unit tests for [FavouriteJourneysRepositoryImpl] (issue #209) against a **real** in-memory
 * Room DB + DAO — same harness as [FavouritesRepositoryImplTest] (Robolectric, no emulator,
 * DAO never mocked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class FavouriteJourneysRepositoryImplTest {
    private lateinit var database: OpenPtvDatabase
    private lateinit var dao: FavouriteJourneyDao
    private lateinit var repository: FavouriteJourneysRepositoryImpl

    private val fixedInstant: Instant = Instant.parse("2026-05-14T09:00:00Z")
    private val fixedClock: Clock =
        object : Clock {
            override fun now(): Instant = fixedInstant
        }

    private val richmond =
        StopMother.aStop().withId(1162).withName("Richmond Station").withSuburb("Richmond").build()
    private val flinders = StopMother.aStop().build()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, OpenPtvDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.favouriteJourneyDao()
        repository = FavouriteJourneysRepositoryImpl(dao = dao, clock = fixedClock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `toggle on an unknown pair inserts it with the clock instant and full endpoints`() =
        runTest {
            repository.observe().test {
                assertThat(awaitItem()).isEmpty()

                repository.toggle(origin = richmond, destination = flinders)

                val favourites = awaitItem()
                val favourite = favourites.single()
                assertThat(favourite.origin).isEqualTo(richmond)
                assertThat(favourite.destination).isEqualTo(flinders)
                assertThat(favourite.addedAt).isEqualTo(fixedInstant)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `toggle on an existing pair removes it`() =
        runTest {
            repository.toggle(origin = richmond, destination = flinders)
            assertThat(repository.observe().first()).hasSize(1)

            repository.toggle(origin = richmond, destination = flinders)

            assertThat(repository.observe().first()).isEmpty()
        }

    @Test
    fun `reversed pair is a distinct favourite`() =
        runTest {
            repository.toggle(origin = richmond, destination = flinders)
            repository.toggle(origin = flinders, destination = richmond)

            val favourites = repository.observe().first()
            assertThat(favourites).hasSize(2)
            assertThat(favourites.map { it.origin.id to it.destination.id })
                .containsExactly(
                    richmond.id to flinders.id,
                    flinders.id to richmond.id,
                )
        }

    @Test
    fun `untoggling A to B leaves B to A alone`() =
        runTest {
            repository.toggle(origin = richmond, destination = flinders)
            repository.toggle(origin = flinders, destination = richmond)

            repository.toggle(origin = richmond, destination = flinders)

            val remaining = repository.observe().first().single()
            assertThat(remaining.origin.id).isEqualTo(flinders.id)
            assertThat(remaining.destination.id).isEqualTo(richmond.id)
        }

    @Test
    fun `isFavourite tracks toggles reactively and is direction-specific`() =
        runTest {
            repository.isFavourite(
                originStopId = richmond.id,
                destinationStopId = flinders.id,
            ).test {
                assertThat(awaitItem()).isFalse()

                repository.toggle(origin = richmond, destination = flinders)
                assertThat(awaitItem()).isTrue()

                repository.toggle(origin = richmond, destination = flinders)
                assertThat(awaitItem()).isFalse()
                cancelAndIgnoreRemainingEvents()
            }

            // The reverse direction never flips the forward key's flow.
            repository.toggle(origin = flinders, destination = richmond)
            assertThat(
                repository.isFavourite(
                    originStopId = richmond.id,
                    destinationStopId = flinders.id,
                ).first(),
            ).isFalse()
        }

    @Test
    fun `isFavourite for an unrelated pair stays false`() =
        runTest {
            repository.toggle(origin = richmond, destination = flinders)

            assertThat(
                repository.isFavourite(
                    originStopId = StopId(9999),
                    destinationStopId = flinders.id,
                ).first(),
            ).isFalse()
        }

    @Test
    fun `observe orders by addedAt so the list renders in starring order`() =
        runTest {
            // Two inserts under a fixed clock share `addedAt`; use the DAO's tie-break-free path
            // by toggling through two distinct clock readings.
            var now = Instant.parse("2026-05-14T09:00:00Z")
            val tickingClock =
                object : Clock {
                    override fun now(): Instant = now
                }
            repository = FavouriteJourneysRepositoryImpl(dao = dao, clock = tickingClock)

            repository.toggle(origin = flinders, destination = richmond)
            now = Instant.parse("2026-05-14T09:05:00Z")
            repository.toggle(origin = richmond, destination = flinders)

            val favourites = repository.observe().first()
            assertThat(favourites.map { it.origin.id })
                .containsExactly(flinders.id, richmond.id)
                .inOrder()
        }
}
