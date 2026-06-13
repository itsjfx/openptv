package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
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
 * Wire-level coverage for [RetrofitRunPatternDataSource]: real Retrofit, real OkHttp,
 * [MockWebServer]. The polling loop and Result wrapping live in `:core:data` — this test only
 * pins the one-shot HTTP contract and the envelope mapping.
 */
class RetrofitRunPatternDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitRunPatternDataSource
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
            RetrofitRunPatternDataSource(
                api = service,
                urlResolver = PtvUrlResolver { path -> "$baseUrl$path" },
            )
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Two-stop Lilydale run: Richmond already served (no estimate), Flinders St upcoming
        // with a live estimate + platform. Sideloads keyed by stringified ids, as PTV emits.
        private val PATTERN_BODY =
            """
            {
              "departures": [
                {"stop_id": 1162, "route_id": 5, "direction_id": 1,
                 "scheduled_departure_utc": "2026-05-14T08:50:00Z"},
                {"stop_id": 1071, "route_id": 5, "direction_id": 1,
                 "scheduled_departure_utc": "2026-05-14T09:10:00Z",
                 "estimated_departure_utc": "2026-05-14T09:12:00Z",
                 "platform_number": "4"}
              ],
              "stops": {
                "1162": {"stop_id": 1162, "stop_name": "Richmond Station ", "stop_suburb": "Richmond"},
                "1071": {"stop_id": 1071, "stop_name": "Flinders Street Railway Station", "stop_suburb": "Melbourne City"}
              },
              "routes": {
                "5": {"route_name": "Lilydale", "route_number": "", "route_type": 0}
              },
              "directions": {
                "1": {"direction_id": 1, "direction_name": "Flinders Street"}
              }
            }
            """.trimIndent()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `200 with pattern returns mapped domain run pattern`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(PATTERN_BODY))

            val result = dataSource.getRunPattern(RunRef("953527"), RouteType.Train)

            assertThat(result.route?.name).isEqualTo("Lilydale")
            assertThat(result.directionName).isEqualTo("Flinders Street")
            assertThat(result.stops).hasSize(2)
            // Stop name joins through the `stops` sideload and gets trimmed.
            assertThat(result.stops[0].stopName).isEqualTo("Richmond Station")
            assertThat(result.stops[0].estimatedDepartureUtc).isNull()
            assertThat(result.stops[0].platform).isNull()
            assertThat(result.stops[1].stopId).isEqualTo(StopId(1071))
            assertThat(result.stops[1].scheduledDepartureUtc)
                .isEqualTo(Instant.parse("2026-05-14T09:10:00Z"))
            assertThat(result.stops[1].estimatedDepartureUtc)
                .isEqualTo(Instant.parse("2026-05-14T09:12:00Z"))
            assertThat(result.stops[1].platform?.value).isEqualTo("4")
        }

    @Test
    fun `200 with missing stop sideload falls back to stop id label`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"departures":[{"stop_id":42,"route_id":5,"direction_id":1,"scheduled_departure_utc":"2026-05-14T09:10:00Z"}],"stops":{},"routes":{},"directions":{}}
                    """.trimIndent(),
                ),
            )

            val result = dataSource.getRunPattern(RunRef("953527"), RouteType.Train)

            assertThat(result.stops[0].stopName).isEqualTo("#42")
            assertThat(result.route).isNull()
            assertThat(result.directionName).isEmpty()
        }

    @Test
    fun `200 with empty departures returns empty pattern`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[]}"""),
            )

            val result = dataSource.getRunPattern(RunRef("953527"), RouteType.Train)

            assertThat(result.stops).isEmpty()
            assertThat(result.route).isNull()
        }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            dataSource.getRunPattern(RunRef("953527"), RouteType.Train)
        }

    @Test(expected = HttpException::class)
    fun `5xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            dataSource.getRunPattern(RunRef("953527"), RouteType.Train)
        }

    @Test(expected = SerializationException::class)
    fun `malformed JSON propagates as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            dataSource.getRunPattern(RunRef("953527"), RouteType.Train)
        }

    @Test
    fun `request URL composes run_ref route_type and expand params`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"departures":[]}"""),
            )

            dataSource.getRunPattern(RunRef("953527"), RouteType.Tram)

            val recorded = server.takeRequest()
            assertThat(recorded.path)
                .isEqualTo("/api/v3/pattern/run/953527/route_type/1?expand=Stop&expand=Route&expand=Direction")
        }
}
