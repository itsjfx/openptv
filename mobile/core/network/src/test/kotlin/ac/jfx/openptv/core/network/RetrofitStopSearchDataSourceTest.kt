package ac.jfx.openptv.core.network

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
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

/**
 * Network-end of the stop-search path: real Retrofit, real OkHttp, [MockWebServer] for the wire.
 * Lives in `:core:network` because it tests the Retrofit-backed [StopSearchDataSource] which can
 * only be constructed within this module (`BackendApiService` is `internal`). The repository-end
 * coverage (Result error wrapping, settings lookup) lives in `:core:data`.
 */
class RetrofitStopSearchDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitStopSearchDataSource
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v3/").toString()
        val service = Retrofit.Builder()
            .baseUrl(server.url("/sentinel/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BackendApiService::class.java)
        dataSource = RetrofitStopSearchDataSource(service)
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 with stops returns mapped domain list`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"stops":[{"stop_id":1071,"stop_name":"Flinders Street Railway Station ","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8183,"stop_longitude":144.9671}]}
                """.trimIndent(),
            ),
        )

        val stops = dataSource.searchStops(baseUrl, "flinders")

        assertThat(stops).hasSize(1)
        // Trim happens at the DTO -> domain boundary in `:core:network`, not in `:core:data`.
        assertThat(stops[0].name).isEqualTo("Flinders Street Railway Station")
    }

    @Test
    fun `200 with empty list returns empty list`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

        val stops = dataSource.searchStops(baseUrl, "zzz")

        assertThat(stops).isEmpty()
    }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        dataSource.searchStops(baseUrl, "bad")
    }

    @Test(expected = HttpException::class)
    fun `5xx propagates as HttpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502))
        dataSource.searchStops(baseUrl, "oops")
    }

    @Test(expected = SerializationException::class)
    fun `malformed JSON propagates as SerializationException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        dataSource.searchStops(baseUrl, "anything")
    }

    @Test
    fun `request URL is composed from baseUrl plus encoded term`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

        dataSource.searchStops(baseUrl, "flinders")

        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/api/v3/search/flinders")
    }
}
