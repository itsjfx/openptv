package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import javax.inject.Inject

/**
 * Use case: fetch the next upcoming [Departure] for a specific `(stopId, routeId, directionId)`
 * triple at a stop. Wraps the existing per-stop departures fetch and filters in memory to the
 * favourited route+direction — no new per-route endpoint, per the phase-04 spec.
 *
 * Returns:
 *  - [Result.Success] with the earliest **upcoming** match — `estimatedDepartureUtc` preferred
 *    over `scheduledDepartureUtc` for ordering (matches the rest of the codebase's
 *    `effectiveDepartureUtc` convention). Departed entries are filtered out using the same
 *    [RelativeTimeFormatter.isDeparted] threshold the stop-detail screen applies, so the
 *    favourites row's "next departure" matches the first row stop-detail would render for the
 *    same route. Without this filter, PTV's `/v3/departures` response (which is anchored at
 *    start-of-day, not "now") would let an already-departed entry win the `minByOrNull` and the
 *    row would show stale times — see issue #82 review feedback.
 *  - `null` when the stop's departures list has no matching upcoming entry (the row renders
 *    "No upcoming departures").
 *  - [Result.Error] when the underlying fetch fails — the favourites VM keeps the previous label
 *    when this happens so the row doesn't flicker (see [`FavouritesViewModel`]).
 *
 * The use case lives in `:core:domain` (not the VM) because the Glance widget (Phase 07) will
 * read the same "next departure per favourite" shape and benefits from the in-memory filter
 * sitting on the domain side of the boundary.
 */
class LoadNextDepartureUseCase
    @Inject
    constructor(
        private val repository: DepartureRepository,
        private val timeFormatter: RelativeTimeFormatter,
    ) {
        suspend operator fun invoke(
            stopId: StopId,
            routeType: RouteType,
            routeId: RouteId,
            directionId: DirectionId,
        ): Result<Departure?> =
            when (val result = repository.getDepartures(stopId, routeType)) {
                is Result.Success -> Result.Success(result.data.pickNext(routeId, directionId))
                is Result.Error -> Result.Error(result.throwable)
                Result.Loading -> Result.Loading
            }

        private fun List<Departure>.pickNext(
            routeId: RouteId,
            directionId: DirectionId,
        ): Departure? =
            asSequence()
                .filter { it.routeId == routeId && it.direction.id == directionId }
                .filterNot { timeFormatter.isDeparted(it.scheduledDepartureUtc, it.estimatedDepartureUtc) }
                .minByOrNull { it.estimatedDepartureUtc ?: it.scheduledDepartureUtc }
    }
