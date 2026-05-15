package ac.jfx.openptv.core.domain

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
 *    `effectiveDepartureUtc` convention). The repository now passes `date_utc=now` and
 *    `look_backwards=false` to PTV (issue #86), so the response is already pre-filtered to
 *    upcoming-only — no additional `isDeparted` filter is needed here. The original review
 *    feedback in issue #82 — that the default `/v3/departures` response was anchored at
 *    start-of-day and let already-departed entries win the `minByOrNull` — no longer applies.
 *  - `null` when the stop's departures list has no matching upcoming entry (the row renders
 *    "No upcoming departures").
 *  - [Result.Error] when the underlying fetch fails — the favourites VM keeps the previous label
 *    when this happens so the row doesn't flicker (see [`FavouritesViewModel`]).
 *
 * The use case lives in `:core:domain` (not the VM) because the Glance widget (Phase 07) will
 * read the same "next departure per favourite" shape and benefits from the projection sitting on
 * the domain side of the boundary.
 */
class LoadNextDepartureUseCase
    @Inject
    constructor(
        private val repository: DepartureRepository,
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
                .minByOrNull { it.estimatedDepartureUtc ?: it.scheduledDepartureUtc }
    }
