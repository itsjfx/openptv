package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.model.toDestinationKey
import javax.inject.Inject

/**
 * Use case: fetch the next upcoming [Departure] for a `(stopId, destinationKey)` pair at a stop,
 * paired with the [Route] that operates it (when PTV sideloads it in the response). Wraps the
 * existing per-stop departures fetch and filters in memory to the departures whose
 * direction-name (lowercased — see [toDestinationKey]) matches the favourited destination — no
 * new per-destination endpoint.
 *
 * Returns:
 *  - [Result.Success] with a [NextDeparture] holding the earliest **upcoming** match —
 *    `estimatedDepartureUtc` preferred over `scheduledDepartureUtc` for ordering — plus the
 *    matching [Route] joined from the response's `routes` sideload, or `null` if PTV omits the
 *    sideload row for that `routeId`. PTV's `/departures` call is already pre-filtered to
 *    upcoming-only (`date_utc=now`, `look_backwards=false`), so no extra `isDeparted` filter is
 *    needed here.
 *  - `Result.Success(null)` when the stop's departures list has no matching upcoming entry (the
 *    row renders "No upcoming departures").
 *  - [Result.Error] when the underlying fetch fails — the favourites VM keeps the previous
 *    label so the row doesn't flicker.
 *
 * The favourites screen joins the route name onto the badge so multi-route destinations like
 * "City" rotate the badge label as routes come up (issue #137).
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
        ): Result<NextDeparture?> =
            when (val result = repository.getDepartures(stopId, routeType)) {
                is Result.Success -> Result.Success(result.data.pickNext(destinationKey))
                is Result.Error -> Result.Error(result.throwable)
                Result.Loading -> Result.Loading
            }

        private fun DeparturesAtStop.pickNext(destinationKey: String): NextDeparture? {
            val departure =
                departures
                    .asSequence()
                    .filter { it.direction.name.toDestinationKey() == destinationKey }
                    .minByOrNull { it.estimatedDepartureUtc ?: it.scheduledDepartureUtc }
                    ?: return null
            val route = routes.firstOrNull { it.id == departure.routeId }
            return NextDeparture(departure = departure, route = route)
        }
    }

/**
 * Pair of the next [Departure] and the [Route] that operates it, joined from the same PTV
 * `/departures` response. `route` is nullable because PTV occasionally omits the sideload row
 * for a route id — callers fall back to `#<routeId>` via
 * [ac.jfx.openptv.core.model.routeDisplayLabel] in that case.
 */
data class NextDeparture(
    val departure: Departure,
    val route: Route?,
)
