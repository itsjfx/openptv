package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.model.DepartureDtoMother
import ac.jfx.openptv.core.network.model.DeparturesResponseDto
import ac.jfx.openptv.core.network.model.DirectionDto
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
    fun `empty response maps to empty list`() {
        val dto = DeparturesResponseDto()
        assertThat(dto.toDomain()).isEmpty()
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

        val departures = dto.toDomain()

        assertThat(departures).hasSize(1)
        val departure = departures[0]
        assertThat(departure.routeId).isEqualTo(RouteId(2))
        assertThat(departure.runRef).isEqualTo(RunRef("OPS-1234"))
        assertThat(departure.scheduledDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:00:00Z"))
        assertThat(departure.estimatedDepartureUtc).isEqualTo(Instant.parse("2026-05-14T09:02:00Z"))
        assertThat(departure.platform?.value).isEqualTo("3")
        assertThat(departure.direction.name).isEqualTo("North Coburg")
        assertThat(departure.direction.id.value).isEqualTo(7)
        assertThat(departure.flags.hasDisruption).isFalse()
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

        val departure = dto.toDomain().single()

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

        val departure = dto.toDomain().single()

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

        val departure = dto.toDomain().single()

        assertThat(departure.platform).isNull()
    }

    @Test
    fun `non-empty disruption ids set hasDisruption flag`() {
        val dto =
            DeparturesResponseDto(
                departures =
                    listOf(
                        DepartureDtoMother.aDepartureDto()
                            .withDisruptionIds(listOf(42L, 43L))
                            .build(),
                    ),
            )

        val departure = dto.toDomain().single()

        assertThat(departure.flags.hasDisruption).isTrue()
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

        val departure = dto.toDomain().single()

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

        val departure = dto.toDomain().single()

        assertThat(departure.direction.name).isEqualTo("North Coburg")
    }
}
