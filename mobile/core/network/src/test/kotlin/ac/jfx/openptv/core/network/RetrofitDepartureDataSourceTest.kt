package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
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
 * Wire-level coverage for [RetrofitDepartureDataSource]: real Retrofit, real OkHttp,
 * [MockWebServer]. The polling loop and Result wrapping live in `:core:data` —
 * this test only pins the one-shot HTTP contract.
 */
class RetrofitDepartureDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitDepartureDataSource
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v3/").toString()
        val service =
            Retrofit.Builder()
                .baseUrl(server.url("/sentinel/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(BackendApiService::class.java)
        dataSource =
            RetrofitDepartureDataSource(
                api = service,
                backendUrl = BackendUrlProvider { baseUrl },
            )
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 with departures returns mapped domain list`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"departures":[{"route_id":1,"run_ref":"OPS-1","direction_id":1,"scheduled_departure_utc":"2026-05-14T09:00:00Z","estimated_departure_utc":"2026-05-14T09:02:00Z","platform_number":"2","disruption_ids":[]}],"directions":{"1":{"direction_id":1,"direction_name":"City"}}}
                    """.trimIndent(),
                ),
            )

            val departures = dataSource.getDepartures(StopId(1071), RouteType.Train)

            assertThat(departures).hasSize(1)
            assertThat(departures[0].direction.name).isEqualTo("City")
        }

    @Test
    fun `200 with empty departures returns empty list`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            val departures = dataSource.getDepartures(StopId(1071), RouteType.Train)

            assertThat(departures).isEmpty()
        }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            dataSource.getDepartures(StopId(1071), RouteType.Train)
        }

    @Test(expected = HttpException::class)
    fun `5xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            dataSource.getDepartures(StopId(1071), RouteType.Train)
        }

    @Test(expected = SerializationException::class)
    fun `malformed JSON propagates as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            dataSource.getDepartures(StopId(1071), RouteType.Train)
        }

    @Test
    fun `request URL contains expand params for run direction route and disruption`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(StopId(1071), RouteType.Tram)

            val recorded = server.takeRequest()
            assertThat(recorded.path)
                .isEqualTo("/api/v3/departures/route_type/1/stop/1071?expand=Run&expand=Direction&expand=Route&expand=Disruption")
        }
}
