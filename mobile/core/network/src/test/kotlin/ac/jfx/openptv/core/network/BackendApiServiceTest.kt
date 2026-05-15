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
 * Exercises the Retrofit-bound [BackendApiService] against [MockWebServer]. No MockK: the goal is
 * to assert the real wire contract — JSON shape, HTTP status mapping, request path — not to mock
 * a fake collaborator.
 *
 * The service now takes a full URL via `@Url` so the test composes one against the mock server,
 * mirroring how `StopSearchRepositoryImpl` does it in production.
 */
class BackendApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: BackendApiService
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/api/v3/").toString()
        val retrofit =
            Retrofit.Builder()
                .baseUrl(server.url("/sentinel/"))
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        service = retrofit.create(BackendApiService::class.java)
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `success response parses into SearchResponseDto`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {"stops":[{"stop_id":1071,"stop_name":"Flinders Street Railway Station","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8183,"stop_longitude":144.9671}],"routes":[],"outlets":[]}
                        """.trimIndent(),
                    ),
            )

            val response = service.searchStops("${baseUrl}search/flinders")

            assertThat(response.stops).hasSize(1)
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/v3/search/flinders")
        }

    @Test(expected = HttpException::class)
    fun `4xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            service.searchStops("${baseUrl}search/does-not-exist")
        }

    @Test(expected = HttpException::class)
    fun `5xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setBody("upstream down"))
            service.searchStops("${baseUrl}search/anything")
        }

    @Test(expected = SerializationException::class)
    fun `malformed JSON surfaces as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
            service.searchStops("${baseUrl}search/anything")
        }

    // -------- getStop --------

    @Test
    fun `getStop success parses StopResponseDto`() =
        runTest {
            // PTV nests `routes` inside the `stop` object — see `StopResponseDto` and #88.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"stop":{"stop_id":1071,"stop_name":"Flinders Street","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8,"stop_longitude":144.96,"routes":[{"route_id":19,"route_name":"Mernda","route_number":"","route_type":0}]}}
                    """.trimIndent(),
                ),
            )

            val response = service.getStop("${baseUrl}stops/1071/route_type/0")

            assertThat(response.stop?.stopName).isEqualTo("Flinders Street")
            assertThat(response.stop?.routes).hasSize(1)
        }

    @Test(expected = HttpException::class)
    fun `getStop 4xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            service.getStop("${baseUrl}stops/99999/route_type/0")
        }

    @Test(expected = HttpException::class)
    fun `getStop 5xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            service.getStop("${baseUrl}stops/1071/route_type/0")
        }

    @Test(expected = SerializationException::class)
    fun `getStop malformed JSON surfaces as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("garbage"))
            service.getStop("${baseUrl}stops/1071/route_type/0")
        }

    // -------- getDepartures --------

    @Test
    fun `getDepartures success parses DeparturesResponseDto`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"departures":[{"route_id":1,"run_ref":"OPS-1","direction_id":1,"scheduled_departure_utc":"2026-05-14T09:00:00Z","estimated_departure_utc":"2026-05-14T09:01:00Z","platform_number":"2","disruption_ids":[]}],"directions":{"1":{"direction_id":1,"direction_name":"City"}}}
                    """.trimIndent(),
                ),
            )

            val response = service.getDepartures("${baseUrl}departures/route_type/0/stop/1071")

            assertThat(response.departures).hasSize(1)
            assertThat(response.directions).hasSize(1)
        }

    @Test(expected = HttpException::class)
    fun `getDepartures 4xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            service.getDepartures("${baseUrl}departures/route_type/0/stop/1071")
        }

    @Test(expected = HttpException::class)
    fun `getDepartures 5xx surfaces as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            service.getDepartures("${baseUrl}departures/route_type/0/stop/1071")
        }

    @Test(expected = SerializationException::class)
    fun `getDepartures malformed JSON surfaces as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{not"))
            service.getDepartures("${baseUrl}departures/route_type/0/stop/1071")
        }
}
