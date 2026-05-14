package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Stop
import kotlinx.serialization.Serializable

/**
 * Top-level wire shape for `GET /api/v3/stops/location/{latitude},{longitude}`. PTV returns the
 * same `stops` block as the search endpoint (DTO-shape identical), so we reuse [StopDto] here —
 * the URL is the only thing that differs, and the response is shaped to the same projection the
 * search list already renders.
 *
 * PTV also returns `status` + occasional `disruptions`; we ignore both — same justification as
 * [SearchResponseDto]. `Json { ignoreUnknownKeys = true }` in the Hilt module covers it.
 */
@Serializable
internal data class NearbyStopsResponseDto(
    val stops: List<StopDto> = emptyList(),
)

internal fun NearbyStopsResponseDto.toDomain(): List<Stop> = stops.map { it.toDomain() }
