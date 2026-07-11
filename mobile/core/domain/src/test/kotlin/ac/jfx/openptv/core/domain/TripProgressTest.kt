package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Unit tests for the pure [TripProgress] derivation behind the pinned bar's "Next stop" line.
 * Fixed clock convention `2026-05-14T09:00:00Z`, same as the rest of the suite; patterns come
 * from [RunPatternMother] (one past stop at 08:50, upcoming stops at 09:05/09:06 and
 * 09:10/09:11).
 */
class TripProgressTest {
    private val now = Instant.parse("2026-05-14T09:00:00Z")

    private companion object {
        /** [RunPatternMother]'s third (terminus) stop, Flinders Street. */
        private const val TERMINUS_STOP_ID = 1071
    }

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

    // --- Rough ETA to the armed alight stop (issue #201) ---

    @Test
    fun `no alight stop requested derives no eta`() {
        val progress = TripProgress.from(RunPatternMother.aRunPattern().build(), now)

        assertThat(progress.alightEta).isNull()
    }

    @Test
    fun `eta to the alight stop counts down from its live estimate`() {
        // Mother's terminus (id 1071): scheduled 09:10, estimated 09:11; clock at 09:00.
        val progress =
            TripProgress.from(RunPatternMother.aRunPattern().build(), now, StopId(TERMINUS_STOP_ID))

        assertThat(progress.alightEta).isEqualTo(11.minutes)
        // The next-stop line is independent of the alight stop.
        assertThat(progress.nextStopName).isEqualTo("East Richmond Station")
    }

    @Test
    fun `eta falls back to the scheduled time when the stop has no estimate`() {
        val scheduleOnly =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPatternStop()
                            .withStopId(TERMINUS_STOP_ID)
                            .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:08:00Z"))
                            .withEstimatedDepartureUtc(null)
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(scheduleOnly, now, StopId(TERMINUS_STOP_ID))

        assertThat(progress.alightEta).isEqualTo(8.minutes)
    }

    @Test
    fun `an alight stop already passed derives no eta`() {
        val afterTerminus = Instant.parse("2026-05-14T09:30:00Z")

        val progress =
            TripProgress.from(
                RunPatternMother.aRunPattern().build(),
                afterTerminus,
                StopId(TERMINUS_STOP_ID),
            )

        assertThat(progress.alightEta).isNull()
    }

    @Test
    fun `an alight stop missing from the pattern derives no eta`() {
        val progress =
            TripProgress.from(RunPatternMother.aRunPattern().build(), now, StopId(999999))

        assertThat(progress.alightEta).isNull()
    }

    @Test
    fun `a repeated alight stop uses its first upcoming occurrence - the city-loop case`() {
        // Same station called at twice (loop run): once in the past, once upcoming.
        val loop =
            RunPatternMother.aRunPattern()
                .withStops(
                    listOf(
                        RunPatternStopMother.aPastPatternStop().withStopId(TERMINUS_STOP_ID).build(),
                        RunPatternStopMother.aPatternStop().build(),
                        RunPatternStopMother.aPatternStop()
                            .withStopId(TERMINUS_STOP_ID)
                            .withScheduledDepartureUtc(Instant.parse("2026-05-14T09:20:00Z"))
                            .withEstimatedDepartureUtc(Instant.parse("2026-05-14T09:21:00Z"))
                            .build(),
                    ),
                )
                .build()

        val progress = TripProgress.from(loop, now, StopId(TERMINUS_STOP_ID))

        assertThat(progress.alightEta).isEqualTo(21.minutes)
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
