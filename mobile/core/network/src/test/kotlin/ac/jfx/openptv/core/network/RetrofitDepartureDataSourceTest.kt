package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
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
                urlResolver = PtvUrlResolver { path -> "$baseUrl$path" },
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

    @Test
    fun `request URL appends date_utc when supplied`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(
                StopId(1071),
                RouteType.Train,
                dateUtc = Instant.parse("2026-05-14T12:30:00Z"),
            )

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("date_utc=2026-05-14T12:30:00Z")
        }

    @Test
    fun `request URL appends max_results when supplied`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(StopId(1071), RouteType.Train, maxResults = 10)

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("max_results=10")
        }

    @Test
    fun `request URL appends both date_utc and max_results when both supplied`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(
                StopId(1071),
                RouteType.Train,
                dateUtc = Instant.parse("2026-05-14T12:30:00Z"),
                maxResults = 5,
            )

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("date_utc=2026-05-14T12:30:00Z")
            assertThat(recorded.path).contains("max_results=5")
        }

    @Test
    fun `request URL appends look_backwards when supplied`() =
        runTest {
            // Issue #86: `look_backwards=false` paired with `date_utc=<now>` is how the data layer
            // tells PTV to omit already-departed entries server-side. The flag must round-trip
            // through the URL verbatim.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(StopId(1071), RouteType.Train, lookBackwards = false)

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("look_backwards=false")
        }

    @Test
    fun `request URL omits look_backwards when not supplied`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(StopId(1071), RouteType.Train)

            val recorded = server.takeRequest()
            assertThat(recorded.path).doesNotContain("look_backwards")
        }

    @Test
    fun `request URL composes date_utc max_results and look_backwards together`() =
        runTest {
            // The repository's head poll passes all three on every tick (issue #86). Pin the
            // composition so a future refactor doesn't silently drop one of them.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[],"directions":{}}"""),
            )

            dataSource.getDepartures(
                StopId(1071),
                RouteType.Train,
                dateUtc = Instant.parse("2026-05-14T12:30:00Z"),
                maxResults = 5,
                lookBackwards = false,
            )

            val recorded = server.takeRequest()
            assertThat(recorded.path).contains("date_utc=2026-05-14T12:30:00Z")
            assertThat(recorded.path).contains("max_results=5")
            assertThat(recorded.path).contains("look_backwards=false")
        }
}
