package ac.jfx.openptv.core.network.model

import ac.jfx.openptv.core.model.Stop
import kotlinx.serialization.Serializable

/**
 * Top-level wire shape for `GET /api/v3/search/{term}`.
 *
 * The PTV response also carries `routes`, `outlets`, and a `status` block — Phase 02 only
 * renders stops, so those fields are ignored. `Json { ignoreUnknownKeys = true }` in the
 * Hilt module means we don't have to mirror them here.
 */
@Serializable
internal data class SearchResponseDto(
    val stops: List<StopDto> = emptyList(),
)

internal fun SearchResponseDto.toDomain(): List<Stop> = stops.map { it.toDomain() }
