package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.NearbyStopsDataSource
import ac.jfx.openptv.core.testing.CoordinatesMother
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Repository-end tests for [NearbyStopsRepositoryImpl]: Result wrapping + cancellation
 * propagation. Mirrors [StopSearchRepositoryImplTest]. The HTTP transport coverage lives in
 * `:core:network`'s `RetrofitNearbyStopsDataSourceTest`.
 */
class NearbyStopsRepositoryImplTest {
    @Test
    fun `success wraps mapped stops in Result Success`() =
        runTest {
            val expected = listOf(StopMother.aStop().build())
            val repo =
                NearbyStopsRepositoryImpl(
                    dataSource = FakeDataSource(returning = expected),
                )

            val result = repo.stopsNear(CoordinatesMother.flindersStreet().build(), RADIUS_M)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEqualTo(expected)
        }

    @Test
    fun `empty wire returns Success with empty list`() =
        runTest {
            val repo =
                NearbyStopsRepositoryImpl(
                    dataSource = FakeDataSource(returning = emptyList()),
                )

            val result = repo.stopsNear(CoordinatesMother.flindersStreet().build(), RADIUS_M)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEmpty()
        }

    @Test
    fun `non-cancellation throwables become Result Error`() =
        runTest {
            val boom = IOException("network down")
            val repo =
                NearbyStopsRepositoryImpl(
                    dataSource = FakeDataSource(throwing = boom),
                )

            val result = repo.stopsNear(CoordinatesMother.flindersStreet().build(), RADIUS_M)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates rather than being swallowed`() =
        runTest {
            val repo =
                NearbyStopsRepositoryImpl(
                    dataSource = FakeDataSource(throwing = CancellationException("scope died")),
                )

            repo.stopsNear(CoordinatesMother.flindersStreet().build(), RADIUS_M)
        }

    @Test
    fun `coordinates and radius are forwarded untouched`() =
        runTest {
            val ds = FakeDataSource(returning = emptyList())
            val repo = NearbyStopsRepositoryImpl(ds)
            val point = CoordinatesMother.federationSquare().build()

            repo.stopsNear(point, 1_200)

            assertThat(ds.calls).containsExactly(point to 1_200)
        }

    private companion object {
        private const val RADIUS_M = 500
    }

    private class FakeDataSource(
        private val returning: List<Stop> = emptyList(),
        private val throwing: Throwable? = null,
    ) : NearbyStopsDataSource {
        val calls: MutableList<Pair<Coordinates, Int>> = mutableListOf()

        override suspend fun stopsNear(
            coordinates: Coordinates,
            radiusMeters: Int,
        ): List<Stop> {
            calls += coordinates to radiusMeters
            throwing?.let { throw it }
            return returning
        }
    }
}
