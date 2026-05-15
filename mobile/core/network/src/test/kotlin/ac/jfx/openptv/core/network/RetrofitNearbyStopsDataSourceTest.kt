package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RouteType
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
 * Network-end of the nearby-stops path: real Retrofit, real OkHttp, [MockWebServer] for the wire.
 * Mirrors [RetrofitStopSearchDataSourceTest] — the repository-end Result wrapping is covered in
 * `:core:data`'s `NearbyStopsRepositoryImplTest`.
 */
class RetrofitNearbyStopsDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitNearbyStopsDataSource
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
            RetrofitNearbyStopsDataSource(
                api = service,
                backendUrl = BackendUrlProvider { baseUrl },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val FLINDERS = Coordinates(lat = -37.8183, lng = 144.9671)
        private const val RADIUS_M = 500
    }

    @Test
    fun `200 with stops returns mapped domain list`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"stops":[{"stop_id":1071,"stop_name":"Flinders Street ","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.8183,"stop_longitude":144.9671}]}
                    """.trimIndent(),
                ),
            )

            val stops = dataSource.stopsNear(FLINDERS, RADIUS_M)

            assertThat(stops).hasSize(1)
            // Same trim-at-the-boundary contract the search path uses.
            assertThat(stops[0].name).isEqualTo("Flinders Street")
        }

    @Test
    fun `200 with empty list returns empty list`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

            val stops = dataSource.stopsNear(FLINDERS, RADIUS_M)

            assertThat(stops).isEmpty()
        }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            dataSource.stopsNear(FLINDERS, RADIUS_M)
        }

    @Test(expected = HttpException::class)
    fun `5xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(502))
            dataSource.stopsNear(FLINDERS, RADIUS_M)
        }

    @Test(expected = SerializationException::class)
    fun `malformed JSON propagates as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            dataSource.stopsNear(FLINDERS, RADIUS_M)
        }

    @Test
    fun `request URL formats lat,lng with six-decimal precision and forwards max_distance`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

            dataSource.stopsNear(Coordinates(lat = -37.8183, lng = 144.9671), 750)

            val recorded = server.takeRequest()
            assertThat(recorded.path)
                .isEqualTo("/api/v3/stops/location/-37.818300,144.967100?max_distance=750")
        }

    @Test
    fun `route_types is repeated once per requested mode in sorted wire-code order`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

            dataSource.stopsNear(
                FLINDERS,
                RADIUS_M,
                routeTypes = setOf(RouteType.Bus, RouteType.Train, RouteType.Tram),
            )

            val recorded = server.takeRequest()
            // Train=0, Tram=1, Bus=2. Sorted-by-wire-code keeps the URL deterministic so the
            // edge / proxy LRU cache hits the same key regardless of `Set` iteration order.
            assertThat(recorded.path)
                .isEqualTo(
                    "/api/v3/stops/location/-37.818300,144.967100?max_distance=$RADIUS_M" +
                        "&route_types=0&route_types=1&route_types=2",
                )
        }

    @Test
    fun `empty routeTypes set omits the parameter entirely`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[]}"""))

            dataSource.stopsNear(FLINDERS, RADIUS_M, routeTypes = emptySet())

            val recorded = server.takeRequest()
            assertThat(recorded.path).doesNotContain("route_types")
        }
}
