package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.model.DepartureDtoMother
import ac.jfx.openptv.core.network.model.DeparturesResponseDto
import ac.jfx.openptv.core.network.model.DirectionDto
import ac.jfx.openptv.core.network.model.DisruptionDtoMother
import ac.jfx.openptv.core.network.model.RouteSideloadDto
import ac.jfx.openptv.core.network.model.toDomain
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Pure mapper test for [DeparturesResponseDto.toDomain]. Real DTOs, no doubles. Covers the
 * direction sideload join, the nullable estimated time, the optional platform string, and the
 * disruption-flag fold.
 */
class DeparturesDtoMapperTest {
    @Test
    fun `empty response maps to empty departures and empty routes`() {
        val dto = DeparturesResponseDto()
        val mapped = dto.toDomain()
        assertThat(mapped.departures).isEmpty()
        assertThat(mapped.routes).isEmpty()
    }

    @Test
    fun `populated row maps to a Departure with all fields`() {
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withRouteId(2)
                            .withDirectionId(7)
                            .withRunRef("OPS-1234")
                            .withScheduledDepartureUtc("2026-05-14T09:00:00Z")
                            .withEstimatedDepartureUtc("2026-05-14T09:02:00Z")
                            .withPlatformNumber("3")
                            .build(),
                    ),
                directions = mapOf("7" to DirectionDto(directionId = 7, directionName = "North Coburg")),
            )

        val departures = dto.toDomain().departures

        assertThat(departures).hasSize(1)
        val departure = departures[0]
        assertThat(departure.routeId).isEqualTo(RouteId(2))
        assertThat(departure.runRef).isEqualTo(RunRef("OPS-1234"))
        assertThat(departure.scheduledDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:00:00Z"))
        assertThat(departure.estimatedDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:02:00Z"))
        assertThat(departure.platform?.value).isEqualTo("3")
        assertThat(departure.direction.name).isEqualTo("North Coburg")
        assertThat(departure.direction.id.value).isEqualTo(7)
        assertThat(departure.hasDisruption).isFalse()
        assertThat(departure.disruptions).isEmpty()
    }

    @Test
    fun `null estimated time passes through as null`() {
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withEstimatedDepartureUtc(null)
                            .build(),
                    ),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.estimatedDepartureUtc).isNull()
    }

    @Test
    fun `null platform passes through as null`() {
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withPlatformNumber(null)
                            .build(),
                    ),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.platform).isNull()
    }

    @Test
    fun `blank platform string is treated as null`() {
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withPlatformNumber("")
                            .build(),
                    ),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.platform).isNull()
    }

    @Test
    fun `disruption ids join to the sideloaded disruption records`() {
        // Issue #177: PTV sideloads the full disruption objects under `disruptions`, keyed by the
        // stringified id; each departure references them by id. The mapper joins the two so the
        // domain Departure carries the title/description the stop-detail sheet renders on tap.
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withDisruptionIds(listOf(42L, 43L))
                            .build(),
                    ),
                disruptions =
                    mapOf(
                        "42" to DisruptionDtoMother.aDisruptionDto().withDisruptionId(42L).withTitle("Buses replace trains").build(),
                        "43" to DisruptionDtoMother.aDisruptionDto().withDisruptionId(43L).withTitle("Lift out of service").build(),
                    ),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.hasDisruption).isTrue()
        assertThat(departure.disruptions.map { it.id.value }).containsExactly(42L, 43L).inOrder()
        assertThat(departure.disruptions.map { it.title })
            .containsExactly("Buses replace trains", "Lift out of service")
    }

    @Test
    fun `disruption ids without a matching sideload record are dropped`() {
        // Defensive: if a departure references a disruption id the `disruptions` map omits, we skip
        // it rather than synthesise a blank record — the row simply shows no indicator for it.
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withDisruptionIds(listOf(42L, 99L))
                            .build(),
                    ),
                disruptions =
                    mapOf("42" to DisruptionDtoMother.aDisruptionDto().withDisruptionId(42L).build()),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.disruptions.map { it.id.value }).containsExactly(42L)
    }

    @Test
    fun `disruption strings are trimmed and blank url falls back to null`() {
        val dto =
            DeparturesResponseDto(
                departures = listOf(DepartureDtoMother.aDepartureDto().withDisruptionIds(listOf(42L)).build()),
                disruptions =
                    mapOf(
                        "42" to
                            DisruptionDtoMother.aDisruptionDto()
                                .withDisruptionId(42L)
                                .withTitle("  Minor delays  ")
                                .withDisruptionType("  Minor Delays  ")
                                .withUrl("")
                                .build(),
                    ),
            )

        val disruption = dto.toDomain().departures.single().disruptions.single()

        assertThat(disruption.title).isEqualTo("Minor delays")
        assertThat(disruption.type).isEqualTo("Minor Delays")
        assertThat(disruption.url).isNull()
    }

    @Test
    fun `missing direction sideload falls back to empty name`() {
        // The mapper is defensive: if PTV omits the sideload, we still emit a Direction with an
        // empty name rather than crash. UI shouldn't observe this in practice because the data
        // source always requests `expand=Direction`.
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withDirectionId(99)
                            .build(),
                    ),
                directions = emptyMap(),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.direction.name).isEmpty()
        assertThat(departure.direction.id.value).isEqualTo(99)
    }

    @Test
    fun `direction name is trimmed at the mapper boundary`() {
        // PTV occasionally emits "North Coburg " — trim once at the mapper so every consumer
        // sees clean strings. Mirrors the StopDto.toDomain trim behaviour.
        val dto =
            DeparturesResponseDto(
                departures = listOf(DepartureDtoMother.aDepartureDto().withDirectionId(7).build()),
                directions = mapOf("7" to DirectionDto(7, "North Coburg ")),
            )

        val departure = dto.toDomain().departures.single()

        assertThat(departure.direction.name).isEqualTo("North Coburg")
    }

    @Test
    fun `routes sideload maps into the routes list keyed by id`() {
        // Issue #137: the favourites screen joins each Departure.routeId back to a Route so it can
        // render the line name on the row badge ("Belgrave"), not a `#<routeId>` fallback.
        val dto =
            DeparturesResponseDto(
                routes =
                    mapOf(
                        "5" to RouteSideloadDto(routeName = "Belgrave", routeNumber = "", routeType = 0),
                        "6" to RouteSideloadDto(routeName = "Cranbourne", routeNumber = "", routeType = 0),
                    ),
            )

        val routes = dto.toDomain().routes

        assertThat(routes).hasSize(2)
        val belgrave = routes.single { it.id == RouteId(5) }
        assertThat(belgrave.name).isEqualTo("Belgrave")
        assertThat(belgrave.routeType).isEqualTo(RouteType.Train)
    }

    @Test
    fun `route name and number are trimmed at the mapper boundary`() {
        val dto =
            DeparturesResponseDto(
                routes = mapOf("19" to RouteSideloadDto(routeName = " North Coburg ", routeNumber = " 19 ", routeType = 1)),
            )

        val route = dto.toDomain().routes.single()

        assertThat(route.name).isEqualTo("North Coburg")
        assertThat(route.number).isEqualTo("19")
    }

    @Test
    fun `routes sideload entries with non-numeric ids are skipped`() {
        // Defensive: PTV should always emit integer route ids, but if a stray key sneaks in we
        // drop it rather than crash.
        val dto =
            DeparturesResponseDto(
                routes =
                    mapOf(
                        "5" to RouteSideloadDto(routeName = "Belgrave", routeType = 0),
                        "bad" to RouteSideloadDto(routeName = "X", routeType = 0),
                    ),
            )

        val routes = dto.toDomain().routes

        assertThat(routes.map { it.id.value }).containsExactly(5)
    }
}
