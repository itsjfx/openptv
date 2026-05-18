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
 * Wire-level coverage for [RetrofitStopDetailDataSource]: real Retrofit, real OkHttp,
 * [MockWebServer] for transport. Mirrors `RetrofitStopSearchDataSourceTest` in structure so
 * the test pattern stays consistent across data sources.
 *
 * Repository-end coverage (Result error wrapping, cancellation propagation) lives in
 * `:core:data`.
 */
class RetrofitStopDetailDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: RetrofitStopDetailDataSource
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
            RetrofitStopDetailDataSource(
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
    fun `200 with stop and routes returns mapped StopDetail`() =
        runTest {
            // PTV nests `routes` inside the `stop` object — see `StopResponseDto` and #88.
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"stop":{"stop_id":1071,"stop_name":"Flinders Street ","stop_suburb":"Melbourne City","route_type":0,"stop_latitude":-37.81,"stop_longitude":144.96,"routes":[{"route_id":19,"route_name":"Mernda","route_number":"","route_type":0}]}}
                    """.trimIndent(),
                ),
            )

            val detail = dataSource.getStopDetail(StopId(1071), RouteType.Train)

            assertThat(detail).isNotNull()
            assertThat(detail!!.stop.name).isEqualTo("Flinders Street")
            assertThat(detail.servingRoutes).hasSize(1)
        }

    @Test
    fun `200 with no stop block returns null`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"routes":[]}"""))

            val detail = dataSource.getStopDetail(StopId(1), RouteType.Train)

            assertThat(detail).isNull()
        }

    @Test(expected = HttpException::class)
    fun `4xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            dataSource.getStopDetail(StopId(99999), RouteType.Train)
        }

    @Test(expected = HttpException::class)
    fun `5xx propagates as HttpException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(502))
            dataSource.getStopDetail(StopId(1), RouteType.Train)
        }

    @Test(expected = SerializationException::class)
    fun `malformed JSON propagates as SerializationException`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
            dataSource.getStopDetail(StopId(1), RouteType.Train)
        }

    @Test
    fun `request URL is composed from baseUrl plus stop id and route type`() =
        runTest {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"stop":{"stop_id":1071,"stop_name":"x","stop_suburb":"","route_type":1,"stop_latitude":0,"stop_longitude":0}}""",
                ),
            )

            dataSource.getStopDetail(StopId(1071), RouteType.Tram)

            val recorded = server.takeRequest()
            assertThat(recorded.path).isEqualTo("/api/v3/stops/1071/route_type/1?stop_location=true&stop_disruptions=true")
        }
}
