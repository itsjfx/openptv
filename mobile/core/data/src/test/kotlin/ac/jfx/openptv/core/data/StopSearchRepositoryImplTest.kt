package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.StopSearchDataSource
import ac.jfx.openptv.core.testing.StopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Repository-end tests for [StopSearchRepositoryImpl]: result wrapping, settings lookup,
 * cancellation propagation. The actual HTTP transport is covered in `:core:network`'s
 * `RetrofitStopSearchDataSourceTest`; here we fake the data source so the tests focus on the
 * repository's contract.
 *
 * `FakeSettingsRepository` is duplicated inline rather than imported from `:core:data-test`
 * because the test fake there is bound through Hilt's `@TestInstallIn` — a heavyweight
 * mechanism intended for instrumented tests, not module-local unit tests.
 */
class StopSearchRepositoryImplTest {

    @Test
    fun `success wraps mapped stops in Result Success`() = runTest {
        val expectedStops = listOf(StopMother.aStop().build())
        val repo = StopSearchRepositoryImpl(
            dataSource = FakeDataSource(returning = expectedStops),
            settings = FakeSettingsRepository(),
        )

        val result = repo.searchStops("flinders")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).isEqualTo(expectedStops)
    }

    @Test
    fun `non-cancellation throwables become Result Error`() = runTest {
        val boom = IOException("network down")
        val repo = StopSearchRepositoryImpl(
            dataSource = FakeDataSource(throwing = boom),
            settings = FakeSettingsRepository(),
        )

        val result = repo.searchStops("anything")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).throwable).isSameInstanceAs(boom)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates rather than being swallowed`() = runTest {
        val repo = StopSearchRepositoryImpl(
            dataSource = FakeDataSource(throwing = CancellationException("scope died")),
            settings = FakeSettingsRepository(),
        )

        repo.searchStops("flinders")
    }

    @Test
    fun `repository reads baseUrl from settings on every call`() = runTest {
        val ds = FakeDataSource(returning = emptyList())
        val settings = FakeSettingsRepository(
            AppSettings(backendBaseUrl = "http://first.local/api/v3/", setupCompleted = true),
        )
        val repo = StopSearchRepositoryImpl(ds, settings)

        repo.searchStops("a")
        settings.setBackendBaseUrl("http://second.local/api/v3/")
        repo.searchStops("b")

        // Both calls hit the data source with the currently-stored base URL — the repo
        // doesn't snapshot the URL once at construction time.
        assertThat(ds.calls).hasSize(2)
        assertThat(ds.calls[0].first).isEqualTo("http://first.local/api/v3/")
        assertThat(ds.calls[1].first).isEqualTo("http://second.local/api/v3/")
    }

    private class FakeDataSource(
        private val returning: List<Stop> = emptyList(),
        private val throwing: Throwable? = null,
    ) : StopSearchDataSource {
        val calls: MutableList<Pair<String, String>> = mutableListOf()

        override suspend fun searchStops(baseUrl: String, term: String): List<Stop> {
            calls += baseUrl to term
            throwing?.let { throw it }
            return returning
        }
    }

    private class FakeSettingsRepository(
        initial: AppSettings = AppSettings(
            backendBaseUrl = "http://test.local/api/v3/",
            setupCompleted = true,
        ),
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state.asStateFlow()
        override suspend fun setBackendBaseUrl(url: String) {
            state.value = state.value.copy(backendBaseUrl = url)
        }
        override suspend fun completeSetup(url: String) {
            state.value = state.value.copy(backendBaseUrl = url, setupCompleted = true)
        }
    }
}
