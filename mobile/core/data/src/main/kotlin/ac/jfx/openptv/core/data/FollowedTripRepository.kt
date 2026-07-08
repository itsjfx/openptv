package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.FollowedTrip
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for the followed trip (issue #200). At most one trip is followed at a
 * time — [follow] replaces whatever was followed before (the *confirmation* for that replace is
 * a UI concern; the repository just stores).
 *
 * Persistence-backed (`:core:datastore`), so a follow survives process death; the pinned
 * "Return to your trip" bar in `:app` collects [followedTrip] across every screen.
 */
interface FollowedTripRepository {
    /** The currently followed trip, `null` when nothing is followed. Emits on every change. */
    val followedTrip: Flow<FollowedTrip?>

    /**
     * Follow [trip], replacing any previously followed one. Also used to *refresh* the stored
     * trip in place (updated `completesAtUtc` from a newer pattern fetch) — same-run overwrites
     * are upserts, not errors.
     */
    suspend fun follow(trip: FollowedTrip)

    /** Stop following. No-op when nothing is followed. */
    suspend fun unfollow()
}
