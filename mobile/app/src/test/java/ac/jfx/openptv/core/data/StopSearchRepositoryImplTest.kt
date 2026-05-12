package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.AppSettings
import ac.jfx.openptv.core.network.BackendApiService
import ac.jfx.openptv.core.testing.FakeSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerializationException

/**
 * End-to-end repository test against a real [BackendApiService] backed by [MockWebServer].
 * No MockK — every error branch is reproducible via mock HTTP responses, which is closer to
 * what the user actually sees in production.
 */
class StopSearchRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: StopSearchRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val service = Retrofit.Builder()
            // Retrofit still requires a baseUrl even when every endpoint uses @Url; the value
            // is a sentinel.
            .baseUrl(server.url("/sentinel/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BackendApiService::class.java)
        val settings = FakeSettingsRepository(
            AppSettings(
                backendBaseUrl = server.url("/api/v3/").toString(),
                setupCompleted = true,
            ),
        )
        repository = StopSearchRepositoryImpl(service, settings)
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 with stops returns Success containing mapped stops`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"stops":[{"stop_id":1071,"stop_name":"Flinders Street Railway Station ","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8183,"stop_longitude":144.9671}]}
                """.trimIndent(),
            ),
        )

        val result = repository.searchStops("flinders")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val stops = (result as Result.Success).data
        assertThat(stops).hasSize(1)
        assertThat(stops[0].name).isEqualTo("Flinders Street Railway Station")
    }

    @Test
    fun `200 with empty list returns Success of empty list`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

        val result = repository.searchStops("zzz")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).isEmpty()
    }

    @Test
    fun `4xx maps to Result Error wrapping HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = repository.searchStops("bad")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).throwable).isInstanceOf(HttpException::class.java)
    }

    @Test
    fun `5xx maps to Result Error wrapping HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))

        val result = repository.searchStops("oops")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).throwable).isInstanceOf(HttpException::class.java)
    }

    @Test
    fun `malformed JSON maps to Result Error wrapping SerializationException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val result = repository.searchStops("anything")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).throwable)
            .isInstanceOf(SerializationException::class.java)
    }

    @Test
    fun `request URL is composed from current settings backendBaseUrl`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

        repository.searchStops("flinders")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v3/search/flinders")
    }
}
