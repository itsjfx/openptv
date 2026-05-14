package ac.jfx.openptv.core.database.entity

import ac.jfx.openptv.core.model.RouteType
import androidx.room.Entity

/**
 * A favourited service at a stop. The unit of favouriting is **a route at a stop**
 * (`(stopId, routeId, directionId)`) — not a whole stop — because the user's primary use case
 * is "show me when the 19 northbound leaves my house stop", not "show me everything that leaves
 * my house stop".
 *
 * Cached display fields (`stopName`, `stopSuburb`, `routeNumber`, `routeName`, `directionName`,
 * `lat`, `lng`) are denormalised onto the row so the favourites list renders a row without a
 * network call. They're refreshed by the repository when the user re-favourites or visits the
 * stop again; this trade is intentional and called out in `docs/mobile/phase-04-favourites.md`.
 *
 * Composite primary key is `(stopId, routeId, directionId)`. `routeType` is part of the row but
 * not the key — two different routes at the same stop already disambiguate via `routeId`, and a
 * `routeId` is unique across route types in the PTV API.
 *
 * `position` is the manual-sort index (`0`-based); the repository keeps it dense via the DAO's
 * `reorder` transaction. `addedAt` is an epoch millisecond timestamp — sufficient for sort-by-
 * recency and keeps the DB plain `INTEGER` without serialisers.
 */
@Entity(
    tableName = "favourite_routes_at_stop",
    primaryKeys = ["stopId", "routeId", "directionId"],
)
data class FavouriteRouteAtStopEntity(
    val stopId: Int,
    val routeType: RouteType,
    val routeId: Int,
    val directionId: Int,
    val stopName: String,
    val stopSuburb: String,
    val routeNumber: String,
    val routeName: String,
    val directionName: String,
    val lat: Double,
    val lng: Double,
    val position: Int,
    val addedAt: Long,
)
