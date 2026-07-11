package ac.jfx.openptv.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Boundary tests for [FollowedTrip.isComplete]. Constructed directly rather than via
 * `FollowedTripMother` because the Mother lives in `:core:testing`, which depends on this
 * module — same shape as `CoordinatesTest`.
 */
class FollowedTripTest {
    private val completesAt = Instant.parse("2026-05-14T09:11:00Z")

    private val trip =
        FollowedTrip(
            runRef = RunRef("953527"),
            routeType = RouteType.Train,
            fromStopId = StopId(1162),
            routeLabel = "Lilydale",
            destinationName = "Flinders Street",
            completesAtUtc = completesAt,
            followedAtUtc = Instant.parse("2026-05-14T09:00:00Z"),
        )

    @Test
    fun `not complete before the final stop arrival`() {
        assertThat(trip.isComplete(completesAt - 10.minutes)).isFalse()
    }

    @Test
    fun `not complete at the final stop arrival`() {
        assertThat(trip.isComplete(completesAt)).isFalse()
    }

    @Test
    fun `not complete while inside the grace period`() {
        assertThat(trip.isComplete(completesAt + FollowedTrip.COMPLETION_GRACE)).isFalse()
    }

    @Test
    fun `complete once past the grace period`() {
        assertThat(trip.isComplete(completesAt + FollowedTrip.COMPLETION_GRACE + 1.seconds)).isTrue()
    }
}
