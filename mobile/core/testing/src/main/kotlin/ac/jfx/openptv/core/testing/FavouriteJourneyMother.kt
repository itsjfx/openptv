package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.Stop
import kotlinx.datetime.Instant

/**
 * Object Mother for [FavouriteJourney] test fixtures (issue #209). Default is the Richmond →
 * Flinders Street train journey, matching `JourneyOptionMother`'s corridor and `:core:database`'s
 * `FavouriteJourneyEntityMother` so failures line up visually across layers.
 *
 * See `~/.claude/skills/object-mother/skill.md`.
 */
class FavouriteJourneyMother private constructor() {
    companion object {
        private val DEFAULT_ORIGIN: Stop =
            StopMother.aStop().withId(1162).withName("Richmond Station").withSuburb("Richmond").build()
        private val DEFAULT_DESTINATION: Stop = StopMother.aStop().build()
        private val DEFAULT_ADDED_AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        fun aFavouriteJourney(): FavouriteJourneyBuilder = FavouriteJourneyBuilder()
    }

    class FavouriteJourneyBuilder {
        private var origin: Stop = DEFAULT_ORIGIN
        private var destination: Stop = DEFAULT_DESTINATION
        private var addedAt: Instant = DEFAULT_ADDED_AT

        fun withOrigin(origin: Stop) = apply { this.origin = origin }

        fun withDestination(destination: Stop) = apply { this.destination = destination }

        fun withAddedAt(addedAt: Instant) = apply { this.addedAt = addedAt }

        fun build(): FavouriteJourney =
            FavouriteJourney(
                origin = origin,
                destination = destination,
                addedAt = addedAt,
            )
    }
}
