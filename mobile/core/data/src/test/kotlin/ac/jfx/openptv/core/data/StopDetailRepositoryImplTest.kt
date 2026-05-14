package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.StopDetailDataSource
import ac.jfx.openptv.core.testing.StopDetailMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Repository-end coverage for [StopDetailRepositoryImpl]: result wrapping, cancellation, and
 * the null → `StopNotFoundException` translation. The HTTP transport is covered in
 * `:core:network`'s `RetrofitStopDetailDataSourceTest`.
 *
 * One-off `FakeDataSource` lives inline because its `calls` recorder is specific to this test
 * file; promoting it to `:core:data-test` would be premature.
 */
class StopDetailRepositoryImplTest {
    @Test
    fun `success wraps the mapped detail in Result Success`() =
        runTest {
            val expected = StopDetailMother.aStopDetail().build()
            val repo = StopDetailRepositoryImpl(FakeDataSource(returning = expected))

            val result = repo.getStopDetail(StopId(1071), RouteType.Train)

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEqualTo(expected)
        }

    @Test
    fun `null data source result surfaces as StopNotFoundException`() =
        runTest {
            val repo = StopDetailRepositoryImpl(FakeDataSource(returning = null))

            val result = repo.getStopDetail(StopId(99999), RouteType.Train)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            val error = result as Result.Error
            assertThat(error.throwable).isInstanceOf(StopNotFoundException::class.java)
        }

    @Test
    fun `non-cancellation throwables become Result Error`() =
        runTest {
            val boom = IOException("network down")
            val repo = StopDetailRepositoryImpl(FakeDataSource(throwing = boom))

            val result = repo.getStopDetail(StopId(1071), RouteType.Train)

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates rather than being swallowed`() =
        runTest {
            val repo =
                StopDetailRepositoryImpl(
                    FakeDataSource(throwing = CancellationException("scope died")),
                )

            repo.getStopDetail(StopId(1071), RouteType.Train)
        }

    @Test
    fun `parameters are forwarded to the data source untouched`() =
        runTest {
            val ds = FakeDataSource(returning = StopDetailMother.aStopDetail().build())
            val repo = StopDetailRepositoryImpl(ds)

            repo.getStopDetail(StopId(42), RouteType.Tram)

            assertThat(ds.calls).containsExactly(StopId(42) to RouteType.Tram)
        }

    private class FakeDataSource(
        private val returning: StopDetail? = null,
        private val throwing: Throwable? = null,
    ) : StopDetailDataSource {
        val calls: MutableList<Pair<StopId, RouteType>> = mutableListOf()

        override suspend fun getStopDetail(
            stopId: StopId,
            routeType: RouteType,
        ): StopDetail? {
            calls += stopId to routeType
            throwing?.let { throw it }
            return returning
        }
    }
}
