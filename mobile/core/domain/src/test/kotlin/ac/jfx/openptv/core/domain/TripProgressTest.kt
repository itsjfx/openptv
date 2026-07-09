package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

/**
 * Unit tests for the pure [TripProgress] derivation behind the pinned bar's "Next stop" line.
 * Fixed clock convention `2026-05-14T09:00:00Z`, same as the rest of the suite; patterns come
 * from [RunPatternMother] (one past stop at 08:50, upcoming stops at 09:05/09:06 and
 * 09:10/09:11).
 */
class TripProgressTest {
    private val now = Instant.parse("2026-05-14T09:00:00Z")

    @Test
    fun `next stop is the first pattern stop still in the future`() {
        val progress = TripProgress.from(RunPatternMother.aRunPattern().build(), now)

        assertThat(progress.nextStopName).isEqualTo("East Richmond Station")
    }

    @Test
    fun `past stops are skipped even when later stops remain`() {
        // Clock between the second stop's estimate (09:06) and the terminus (09:11).
        val midTrip = Instant.parse("2026-05-14T09:07:00Z")

        val progress = TripProgress.from(RunPatternMother.aRunPattern().build(), midTrip)

        assertThat(progress.nextStopName).isEqualTo("Flinders Street Railway Station")
    }

    @Test
    fun `all stops in the past means no next stop - trip effectively over`() {
        val afterTerminus = Instant.parse("2026-05-14T10:00:00Z")

        val progress = TripProgress.from(RunPatternMother.aRunPattern().build(), afterTerminus)

        assertThat(progress.nextStopName).isNull()
    }

    @Test
    fun `empty pattern derives no next stop`() {
        val progress = TripProgress.from(RunPatternMother.aRunPattern().withStops(emptyList()).build(), now)

        assertThat(progress.nextStopName).isNull()
    }

    @Test
    fun `estimate is preferred - a stop scheduled in the past but estimated late is still upcoming`() {
        // Scheduled 08:55 (past), running late with a live estimate of 09:04 (future).
        val runningLate =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPatternStop()
                            .withStopName("Burnley Station")
                            .withScheduledDepartureUtc(Instant.parse("2026-05-14T08:55:00Z"))
                            .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:04:00Z"))
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(runningLate, now)

        assertThat(progress.nextStopName).isEqualTo("Burnley Station")
    }

    @Test
    fun `estimate is preferred - a stop scheduled in the future but estimated past is not upcoming`() {
        // Running early: scheduled 09:05 (future) but the vehicle already departed at 08:58.
        val ranEarly =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPatternStop()
                            .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                            .withEstimatedDepartureUtc(Instant.parse("2026-05-14T08:58:00Z"))
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(ranEarly, now)

        assertThat(progress.nextStopName).isNull()
    }

    @Test
    fun `no estimates falls back to scheduled times - the tram pattern quirk`() {
        val scheduleOnly =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPastPatternStop().build(),
                        RunPatternStopMother.aPatternStop()
                            .withStopName("Glenferrie Station")
                            .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:05:00Z"))
                            .withEstimatedDepartureUtc(null)
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(scheduleOnly, now)

        assertThat(progress.nextStopName).isEqualTo("Glenferrie Station")
    }

    @Test
    fun `a departure exactly at now is not upcoming`() {
        val boundary =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPatternStop()
                            .withScheduledDepartureUtc(now)
                            .withEstimatedDepartureUtc(now)
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(boundary, now)

        assertThat(progress.nextStopName).isNull()
    }
}
