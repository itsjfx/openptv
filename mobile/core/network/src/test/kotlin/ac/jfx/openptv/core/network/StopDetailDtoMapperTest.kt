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
                        .withRoutes(
                            listOf(
                                RouteDto(
                                    routeId = 19,
                                    routeName = "Mernda ",
                                    routeNumber = "  ",
                                    routeType = 0,
                                ),
                            ),
                        )
                        .build(),
            )

        val detail = response.toDomain(requestedRouteType = RouteType.Train)

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
        val response = StopResponseDto(stop = null)
        assertThat(response.toDomain(requestedRouteType = RouteType.Train)).isNull()
    }

    @Test
    fun `response with stop but no routes maps to empty servingRoutes`() {
        val response = StopResponseDto(stop = StopDetailsDtoMother.aStopDetailsDto().build())

        val detail = response.toDomain(requestedRouteType = RouteType.Train)

        assertThat(detail).isNotNull()
        assertThat(detail!!.servingRoutes).isEmpty()
    }

    @Test
    fun `route mapper translates route_type code to domain enum`() {
        val response =
            StopResponseDto(
                stop =
                    StopDetailsDtoMother.aStopDetailsDto()
                        .withRouteType(1)
                        .withRoutes(
                            listOf(
                                RouteDto(routeId = 1, routeName = "19", routeNumber = "19", routeType = 1),
                            ),
                        )
                        .build(),
            )

        val detail = response.toDomain(requestedRouteType = RouteType.Tram)!!

        assertThat(detail.stop.routeType).isEqualTo(RouteType.Tram)
        assertThat(detail.servingRoutes[0].routeType).isEqualTo(RouteType.Tram)
    }

    @Test
    fun `routes are nested inside the stop block in PTV responses`() {
        // Regression coverage for the bug fixed alongside issue #88: the real PTV response
        // returns the serving routes under `stop.routes`, not at the top level. The mapper
        // must read them from inside the stop object.
        val response =
            StopResponseDto(
                stop =
                    StopDetailsDtoMother.aStopDetailsDto()
                        .withRouteType(1)
                        .withRoutes(
                            listOf(
                                RouteDto(routeId = 958, routeName = "Vermont South", routeNumber = "75", routeType = 1),
                                RouteDto(routeId = 940, routeName = "Waterfront City", routeNumber = "70", routeType = 1),
                            ),
                        )
                        .build(),
            )

        val detail = response.toDomain(requestedRouteType = RouteType.Tram)!!

        assertThat(detail.servingRoutes.map { it.number }).containsExactly("75", "70").inOrder()
    }

    @Test
    fun `serving routes are filtered to the requested route type at a shared stop_id`() {
        // Regression coverage for issue #175. Richmond's metro platforms and the V/Line platform
        // share `stop_id` 1162, and PTV's `/stops/{id}/route_type/{type}` endpoint ignores the
        // path `route_type` for the `routes` array — it returns every route serving the physical
        // stop. Captured shape from `/api/v3/stops/1162/route_type/3`: metro (route_type 0) lines
        // mixed in with the V/Line (route_type 3) services. When the caller asked for V/Line, the
        // mapper must drop the metro routes so the Nearby sheet's Routes chips match the tapped
        // mode (departures are already fetched per `(stopId, routeType)` and were correct).
        val response =
            StopResponseDto(
                stop =
                    StopDetailsDtoMother.aStopDetailsDto()
                        .withStopName("Richmond Railway Station")
                        .withRouteType(3)
                        .withRoutes(
                            listOf(
                                RouteDto(routeId = 1, routeName = "Alamein", routeNumber = "", routeType = 0),
                                RouteDto(routeId = 2, routeName = "Belgrave", routeNumber = "", routeType = 0),
                                RouteDto(
                                    routeId = 3,
                                    routeName = "Bairnsdale - Melbourne via Sale & Traralgon",
                                    routeNumber = "",
                                    routeType = 3,
                                ),
                                RouteDto(
                                    routeId = 4,
                                    routeName = "Traralgon - Melbourne via Morwell & Moe & Pakenham",
                                    routeNumber = "",
                                    routeType = 3,
                                ),
                            ),
                        )
                        .build(),
            )

        val detail = response.toDomain(requestedRouteType = RouteType.VLine)!!

        assertThat(detail.servingRoutes.map { it.routeType })
            .containsExactly(RouteType.VLine, RouteType.VLine)
        assertThat(detail.servingRoutes.map { it.name })
            .containsExactly(
                "Bairnsdale - Melbourne via Sale & Traralgon",
                "Traralgon - Melbourne via Morwell & Moe & Pakenham",
            )
            .inOrder()
    }

    @Test
    fun `requesting metro at a shared stop_id keeps only metro routes`() {
        // Mirror of the #175 case from the other side: tapping the metro Richmond pin must show
        // only the metro lines, not the co-located V/Line services.
        val response =
            StopResponseDto(
                stop =
                    StopDetailsDtoMother.aStopDetailsDto()
                        .withRouteType(0)
                        .withRoutes(
                            listOf(
                                RouteDto(routeId = 1, routeName = "Alamein", routeNumber = "", routeType = 0),
                                RouteDto(
                                    routeId = 3,
                                    routeName = "Bairnsdale - Melbourne via Sale & Traralgon",
                                    routeNumber = "",
                                    routeType = 3,
                                ),
                            ),
                        )
                        .build(),
            )

        val detail = response.toDomain(requestedRouteType = RouteType.Train)!!

        assertThat(detail.servingRoutes.map { it.name }).containsExactly("Alamein")
    }
}
