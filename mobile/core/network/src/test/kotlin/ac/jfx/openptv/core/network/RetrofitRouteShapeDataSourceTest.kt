package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
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

/**
 * Wire-level coverage for [RetrofitRouteShapeDataSource] (issue #187): real Retrofit, real OkHttp,
 * [MockWebServer]. The load-bearing thing here is the geopath `paths` string parser — PTV encodes
 * each polyline as a whitespace-separated run of `"lat, lng"` pairs, and getting the comma/space
 * tokenisation wrong silently produces a blank line on the map.
 */
class RetrofitRouteShapeDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitRouteShapeDataSource
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
            RetrofitRouteShapeDataSource(
                api = service,
                urlResolver = PtvUrlResolver { path -> "$baseUrl$path" },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Two stops, two directions. Each direction's geopath is one `paths` string of three
        // points. The exact shape PTV emits: `"lat, lng lat, lng lat, lng"`.
        private val SHAPE_BODY =
            """
            {
              "stops": [
                {"stop_id": 1013, "stop_latitude": -37.8694878, "stop_longitude": 144.993515},
                {"stop_id": 1014, "stop_latitude": -37.8665, "stop_longitude": 144.9988}
              ],
              "geopath": [
                {"direction_id": 12, "valid_from": "2026-07-03", "valid_to": "2026-12-31",
                 "paths": ["-37.8267, 145.0582 -37.8270, 145.0590 -37.8275, 145.0601"]},
                {"direction_id": 1, "valid_from": "2026-07-03", "valid_to": "2026-12-31",
                 "paths": ["-37.8275, 145.0601 -37.8270, 145.0590 -37.8267, 145.0582"]}
              ]
            }
            """.trimIndent()
    }

    @Test
    fun `parses geopath paths string into ordered polyline coordinates`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHAPE_BODY))

            val shape = dataSource.getRouteShape(RouteId(12), RouteType.Train)

            val direction12 = shape.geopathByDirection.getValue(12)
            assertThat(direction12).hasSize(1)
            val polyline = direction12.first()
            assertThat(polyline).hasSize(3)
            // First point: lat then lng, comma stripped, in order.
            assertThat(polyline.first().lat).isWithin(1e-7).of(-37.8267)
            assertThat(polyline.first().lng).isWithin(1e-7).of(145.0582)
            assertThat(polyline.last().lat).isWithin(1e-7).of(-37.8275)
        }

    @Test
    fun `keys geopath by direction id`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHAPE_BODY))

            val shape = dataSource.getRouteShape(RouteId(12), RouteType.Train)

            assertThat(shape.geopathByDirection.keys).containsExactly(12, 1)
            // geopathFor picks the matching direction; falls back to any when missing.
            assertThat(shape.geopathFor(12)).isEqualTo(shape.geopathByDirection[12])
            assertThat(shape.geopathFor(99)).isNotEmpty()
        }

    @Test
    fun `maps stop coordinates by stop id`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody(SHAPE_BODY))

            val shape = dataSource.getRouteShape(RouteId(12), RouteType.Train)

            val coord = shape.stopCoordinates.getValue(StopId(1013))
            assertThat(coord.lat).isWithin(1e-7).of(-37.8694878)
            assertThat(coord.lng).isWithin(1e-7).of(144.993515)
        }

    @Test
    fun `empty geopath yields empty map and graceful fallback`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"stops":[],"geopath":[]}""",
                ),
            )

            val shape = dataSource.getRouteShape(RouteId(12), RouteType.Train)

            assertThat(shape.geopathByDirection).isEmpty()
            assertThat(shape.geopathFor(12)).isEmpty()
            assertThat(shape.stopCoordinates).isEmpty()
        }

    @Test
    fun `malformed coordinate token is skipped without failing the fetch`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"stops":[],"geopath":[{"direction_id":0,
                     "paths":["-37.8267, 145.0582 garbage -37.8275, 145.0601"]}]}
                    """.trimIndent(),
                ),
            )

            val shape = dataSource.getRouteShape(RouteId(12), RouteType.Train)

            // The good pair survives; the "garbage -37.8275" pair fails toDouble and is dropped.
            val polyline = shape.geopathByDirection.getValue(0).first()
            assertThat(polyline).hasSize(1)
            assertThat(polyline.first().lng).isWithin(1e-7).of(145.0582)
        }

    @Test
    fun `request URL composes route_id route_type and include_geopath`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"stops":[],"geopath":[]}"""))

            dataSource.getRouteShape(RouteId(12), RouteType.Train)

            val recorded = server.takeRequest()
            assertThat(recorded.path)
                .isEqualTo("/api/v3/stops/route/12/route_type/0?include_geopath=true")
        }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400))
            dataSource.getRouteShape(RouteId(12), RouteType.Train)
        }
}
