package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * Domain projection of a favourited service at a stop. The unit of favouriting is **a route at a
 * stop** (`(stopId, routeId, directionId)`) — not a whole stop — because the user's primary use
 * case is "show me when the 19 northbound leaves my house stop", not "show me everything that
 * leaves my house stop".
 *
 * Mirrors `core.database.entity.FavouriteRouteAtStopEntity` 1:1 (the entity stays internal to
 * `:core:database`; this is the type domain callers see). Cached display fields (`stopName`,
 * `stopSuburb`, `routeNumber`, `routeName`, `directionName`, `lat`, `lng`) are denormalised onto
 * the row so the favourites list can render without a network call. They're refreshed by the
 * repository when the user re-favourites or re-visits the stop.
 *
 * `position` is the manual-sort index (0-based). `addedAt` is the wall-clock instant the row was
 * created, kept as a real [Instant] in the domain (the entity stores it as epoch ms; the mapper
 * does the convert).
 */
data class FavouriteRouteAtStop(
    val stopId: StopId,
    val routeType: RouteType,
    val routeId: RouteId,
    val directionId: DirectionId,
    val stopName: String,
    val stopSuburb: String,
    val routeNumber: String,
    val routeName: String,
    val directionName: String,
    val lat: Double,
    val lng: Double,
    val position: Int,
    val addedAt: Instant,
)
