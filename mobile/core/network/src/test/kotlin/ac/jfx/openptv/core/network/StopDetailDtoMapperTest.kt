package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.network.model.RouteDto
import ac.jfx.openptv.core.network.model.StopDetailsDtoMother
import ac.jfx.openptv.core.network.model.StopResponseDto
import ac.jfx.openptv.core.network.model.toDomain
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure mapper test for [StopResponseDto.toDomain]. The mapper trims whitespace on both stop and
 * route fields (PTV emits trailing spaces in real responses), maps each documented `route_type`
 * code, and returns `null` when the top-level `stop` block is absent so the repository can
 * convert that into a `Result.Error`.
 */
class StopDetailDtoMapperTest {
    @Test
    fun `populated response maps to StopDetail with trimmed fields`() {
        val response =
            StopResponseDto(
                stop =
                    StopDetailsDtoMother.aStopDetailsDto()
                        .withStopName("Flinders Street Railway Station ")
                        .withStopSuburb("Melbourne City  ")
                        .build(),
                routes =
                    listOf(
                        RouteDto(
                            routeId = 19,
                            routeName = "Mernda ",
                            routeNumber = "  ",
                            routeType = 0,
                        ),
                    ),
            )

        val detail = response.toDomain()

        assertThat(detail).isNotNull()
        assertThat(detail!!.stop.name).isEqualTo("Flinders Street Railway Station")
        assertThat(detail.stop.suburb).isEqualTo("Melbourne City")
        assertThat(detail.stop.routeType).isEqualTo(RouteType.Train)
        assertThat(detail.servingRoutes).hasSize(1)
        assertThat(detail.servingRoutes[0].name).isEqualTo("Mernda")
        assertThat(detail.servingRoutes[0].number).isEmpty()
    }

    @Test
    fun `response with no stop block returns null`() {
        val response = StopResponseDto(stop = null, routes = emptyList())
        assertThat(response.toDomain()).isNull()
    }

    @Test
    fun `response with stop but no routes maps to empty servingRoutes`() {
        val response = StopResponseDto(stop = StopDetailsDtoMother.aStopDetailsDto().build())

        val detail = response.toDomain()

        assertThat(detail).isNotNull()
        assertThat(detail!!.servingRoutes).isEmpty()
    }

    @Test
    fun `route mapper translates route_type code to domain enum`() {
        val response =
            StopResponseDto(
                stop = StopDetailsDtoMother.aStopDetailsDto().withRouteType(1).build(),
                routes =
                    listOf(
                        RouteDto(routeId = 1, routeName = "19", routeNumber = "19", routeType = 1),
                    ),
            )

        val detail = response.toDomain()!!

        assertThat(detail.stop.routeType).isEqualTo(RouteType.Tram)
        assertThat(detail.servingRoutes[0].routeType).isEqualTo(RouteType.Tram)
    }
}
