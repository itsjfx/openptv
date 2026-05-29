package ac.jfx.openptv.core.database.entity

import ac.jfx.openptv.core.model.RouteType
import androidx.room.Entity

/**
 * A favourited destination at a stop. The unit of favouriting is **a destination at a stop**
 * (`(stopId, destinationKey)`) — not a route — so a single favourite covers every route the user
 * sees feeding a destination block on stop-detail. At Caulfield the "City" block is fed by three
 * train lines; one favourite covers them all. If PTV adds or removes a feeding route the favourite
 * is unaffected because the key is the destination, not the routes.
 *
 * `destinationKey` is the lowercased direction name — the same shape `GroupKey.destination` uses
 * in `:feature:stop-detail`. Single source of truth lives in `ac.jfx.openptv.core.model.toDestinationKey`.
 *
 * Cached display fields (`stopName`, `stopSuburb`, `destinationName`, `lat`, `lng`) are denormalised
 * onto the row so the favourites list renders without a network call. They're refreshed when the
 * user re-favourites.
 *
 * `position` is the manual-sort index (`0`-based). `addedAt` is an epoch millisecond timestamp.
 */
@Entity(
    tableName = "favourite_destinations_at_stop",
    primaryKeys = ["stopId", "destinationKey"],
)
data class FavouriteDestinationAtStopEntity(
    val stopId: Int,
    val destinationKey: String,
    val routeType: RouteType,
    val stopName: String,
    val stopSuburb: String,
    val destinationName: String,
    val lat: Double,
    val lng: Double,
    val position: Int,
    val addedAt: Long,
)
