package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.StopSearchDataSource
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Repository-end tests for [StopSearchRepositoryImpl]: result wrapping, cancellation
 * propagation. The actual HTTP transport is covered in `:core:network`'s
 * `RetrofitStopSearchDataSourceTest`; here we fake the data source so the tests focus on the
 * repository's contract.
 *
 * URL resolution moved out of the repository in gap #4: `StopSearchDataSource.searchStops`
 * no longer takes a `baseUrl` parameter. The "reads URL / signing-mode from settings on every
 * call" test lives next to the resolver itself in `SettingsPtvUrlResolverTest`.
 *
 * The inline `FakeDataSource` stays: it's a one-off `StopSearchDataSource` fake whose `calls`
 * recorder is specific to this test class. Promoting it to `:core:data-test` would be premature.
 */
class StopSearchRepositoryImplTest {
    @Test
    fun `success wraps mapped stops in Result Success`() =
        runTest {
            val expectedStops = listOf(StopMother.aStop().build())
            val repo =
                StopSearchRepositoryImpl(
                    dataSource = FakeDataSource(returning = expectedStops),
                )

            val result = repo.searchStops("flinders")

            assertThat(result).isInstanceOf(Result.Success::class.java)
            assertThat((result as Result.Success).data).isEqualTo(expectedStops)
        }

    @Test
    fun `non-cancellation throwables become Result Error`() =
        runTest {
            val boom = IOException("network down")
            val repo =
                StopSearchRepositoryImpl(
                    dataSource = FakeDataSource(throwing = boom),
                )

            val result = repo.searchStops("anything")

            assertThat(result).isInstanceOf(Result.Error::class.java)
            assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
        }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates rather than being swallowed`() =
        runTest {
            val repo =
                StopSearchRepositoryImpl(
                    dataSource = FakeDataSource(throwing = CancellationException("scope died")),
                )

            repo.searchStops("flinders")
        }

    @Test
    fun `term is passed through to the data source untouched`() =
        runTest {
            val ds = FakeDataSource(returning = emptyList())
            val repo = StopSearchRepositoryImpl(ds)

            repo.searchStops("flinders street")

            assertThat(ds.calls).containsExactly("flinders street")
        }

    private class FakeDataSource(
        private val returning: List<Stop> = emptyList(),
        private val throwing: Throwable? = null,
    ) : StopSearchDataSource {
        val calls: MutableList<String> = mutableListOf()

        override suspend fun searchStops(term: String): List<Stop> {
            calls += term
            throwing?.let { throw it }
            return returning
        }
    }
}
