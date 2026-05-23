package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.model.toDestinationKey
import javax.inject.Inject

/**
 * Use case: fetch the next upcoming [Departure] for a `(stopId, destinationKey)` pair at a stop.
 * Wraps the existing per-stop departures fetch and filters in memory to the departures whose
 * direction-name (lowercased — see [toDestinationKey]) matches the favourited destination — no
 * new per-destination endpoint.
 *
 * Returns:
 *  - [Result.Success] with the earliest **upcoming** match — `estimatedDepartureUtc` preferred
 *    over `scheduledDepartureUtc` for ordering. PTV's `/departures` call is already
 *    pre-filtered to upcoming-only (`date_utc=now`, `look_backwards=false`), so no extra
 *    `isDeparted` filter is needed here.
 *  - `null` when the stop's departures list has no matching upcoming entry (the row renders
 *    "No upcoming departures").
 *  - [Result.Error] when the underlying fetch fails — the favourites VM keeps the previous
 *    label so the row doesn't flicker.
 *
 * The Departure returned carries enough for the favourites screen to derive the live next-route
 * badge (which line is actually next), so multi-route destinations like "City" rotate the badge
 * label as routes come up.
 */
class LoadNextDepartureUseCase
    @Inject
    constructor(
        private val repository: DepartureRepository,
    ) {
        suspend operator fun invoke(
            stopId: StopId,
            routeType: RouteType,
            destinationKey: String,
        ): Result<Departure?> =
            when (val result = repository.getDepartures(stopId, routeType)) {
                is Result.Success -> Result.Success(result.data.pickNext(destinationKey))
                is Result.Error -> Result.Error(result.throwable)
                Result.Loading -> Result.Loading
            }

        private fun List<Departure>.pickNext(destinationKey: String): Departure? =
            asSequence()
                .filter { it.direction.name.toDestinationKey() == destinationKey }
                .minByOrNull { it.estimatedDepartureUtc ?: it.scheduledDepartureUtc }
    }
