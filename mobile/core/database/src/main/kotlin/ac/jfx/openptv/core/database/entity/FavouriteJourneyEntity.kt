package ac.jfx.openptv.core.database.entity

import ac.jfx.openptv.core.model.RouteType
import androidx.room.Entity

/**
 * A favourited origin→destination journey (issue #209). Keyed on the **ordered** stop pair
 * (`originStopId`, `destinationStopId`) — A→B and B→A are distinct favourites by design, so the
 * pair is the primary key as-is rather than a normalised (min, max) form.
 *
 * Both endpoints are denormalised in full (name, suburb, route type, lat/lng) so the favourites
 * screen can reconstruct two `Stop` domain objects without a network call — the journey
 * repository takes `Stop`s, and the tap-through prefills the planner with them. Display fields
 * refresh when the user re-favourites the pair (`@Upsert` semantics), same trade as
 * [FavouriteDestinationAtStopEntity].
 *
 * `addedAt` is an epoch millisecond timestamp; the list renders in insertion order (no manual
 * `position` column — journey favourites don't participate in the drag-to-reorder UI).
 */
@Entity(
    tableName = "favourite_journeys",
    primaryKeys = ["originStopId", "destinationStopId"],
)
data class FavouriteJourneyEntity(
    val originStopId: Int,
    val originStopName: String,
    val originStopSuburb: String,
    val originRouteType: RouteType,
    val originLat: Double,
    val originLng: Double,
    val destinationStopId: Int,
    val destinationStopName: String,
    val destinationStopSuburb: String,
    val destinationRouteType: RouteType,
    val destinationLat: Double,
    val destinationLng: Double,
    val addedAt: Long,
)
