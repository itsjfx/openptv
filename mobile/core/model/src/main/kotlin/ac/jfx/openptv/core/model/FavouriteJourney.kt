package ac.jfx.openptv.core.model

import kotlinx.datetime.Instant

/**
 * A favourited origin→destination journey (issue #209). The unit of favouriting is the ordered
 * stop pair — A→B and B→A are distinct favourites, because "when's my next train home" and
 * "when's my next train to work" are different questions.
 *
 * Both endpoints are denormalised as full [Stop] projections so the favourites screen can fetch
 * the next direct service ([ac.jfx.openptv.core.data.JourneyPlannerRepository] takes `Stop`s) and
 * prefill the journey planner without a network round-trip. Display fields refresh when the user
 * re-favourites the pair.
 *
 * `addedAt` is the wall-clock instant the row was created; the list renders in insertion order.
 */
data class FavouriteJourney(
    val origin: Stop,
    val destination: Stop,
    val addedAt: Instant,
)
