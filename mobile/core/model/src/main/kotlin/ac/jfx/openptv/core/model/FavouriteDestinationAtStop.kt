package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * Domain projection of a favourited destination at a stop. The unit of favouriting is
 * **a destination at a stop** (`(stopId, destinationKey)`) — not a route — so a single favourite
 * covers every route the user sees feeding a destination block on stop-detail.
 *
 * `destinationKey` is the lowercased direction name — see [toDestinationKey] for the rule. It
 * matches the `GroupKey.destination` value the stop-detail screen uses to group departures, so
 * "favourite the block as the user sees it" is well-defined regardless of how many routes feed
 * that block.
 *
 * Cached display fields (`stopName`, `stopSuburb`, `destinationName`, `lat`, `lng`) are
 * denormalised onto the row so the favourites list can render without a network call. They're
 * refreshed when the user re-favourites.
 *
 * `position` is the manual-sort index (0-based). `addedAt` is the wall-clock instant the row was
 * created (epoch ms on disk, [Instant] in the domain).
 */
data class FavouriteDestinationAtStop(
    val stopId: StopId,
    val destinationKey: String,
    val routeType: RouteType,
    val stopName: String,
    val stopSuburb: String,
    val destinationName: String,
    val lat: Double,
    val lng: Double,
    val position: Int,
    val addedAt: Instant,
)

/**
 * Canonical destination-key derivation: lowercase the display name. Matches the
 * `direction.name.lowercase()` rule the stop-detail grouping uses (`GroupKey.destination`) and the
 * `LOWER(directionName)` rule the v1→v2 Room migration uses. Change one, change the other.
 */
fun String.toDestinationKey(): String = lowercase()
