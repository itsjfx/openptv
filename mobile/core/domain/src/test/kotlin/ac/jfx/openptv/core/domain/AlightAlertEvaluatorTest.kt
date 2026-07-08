package ac.jfx.openptv.core.domain

import ac.jfx.openptv.core.model.Coordinates
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.testing.AlightAlertMother
import ac.jfx.openptv.core.testing.RunPatternMother
import ac.jfx.openptv.core.testing.RunPatternStopMother
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [AlightAlertEvaluator] — the alert-stage state machine (issue #201). Pure
 * inputs, so no dispatchers or fakes: patterns come from [RunPatternMother] and the clock is a
 * plain [Instant] per test.
 *
 * Fixture geography: a five-stop run at 09:05 / 09:10 / 09:15 / 09:20 / 09:25, alight at the
 * 4th stop (id 40). Stops are 1 km apart on a north-south line so the GPS thresholds are easy
 * to reason about.
 */
class AlightAlertEvaluatorTest {
    private val evaluator = AlightAlertEvaluator()

    private fun stop(
        id: Int,
        scheduled: String,
        estimated: String? = null,
        coordinates: Coordinates? = null,
    ) = RunPatternStopMother.aPatternStop()
        .withStopId(id)
        .withStopName("Stop $id")
        .withScheduledDepartureUtc(Instant.parse(scheduled))
        .withEstimatedDepartureUtc(estimated?.let(Instant::parse))
        .withCoordinates(coordinates)
        .build()

    /** ~1 km of latitude per 0.009°; stops run south-to-north towards the terminus. */
    private fun coords(index: Int) = Coordinates(lat = -37.850 + index * 0.009, lng = 144.960)

    private fun fiveStopPattern(
        estimated: Boolean,
        withCoordinates: Boolean = false,
    ): RunPattern {
        fun est(scheduled: String): String? = scheduled.takeIf { estimated }
        return RunPatternMother.aRunPattern()
            .withStops(
                listOf(
                    stop(10, "2026-05-14T09:05:00Z", est("2026-05-14T09:05:00Z"), coords(0).takeIf { withCoordinates }),
                    stop(20, "2026-05-14T09:10:00Z", est("2026-05-14T09:10:00Z"), coords(1).takeIf { withCoordinates }),
                    stop(30, "2026-05-14T09:15:00Z", est("2026-05-14T09:15:00Z"), coords(2).takeIf { withCoordinates }),
                    stop(40, "2026-05-14T09:20:00Z", est("2026-05-14T09:20:00Z"), coords(3).takeIf { withCoordinates }),
                    stop(50, "2026-05-14T09:25:00Z", est("2026-05-14T09:25:00Z"), coords(4).takeIf { withCoordinates }),
                ),
            )
            .build()
    }

    private fun alertAtStop40() =
        AlightAlertMother.anAlightAlert()
            .withStopId(40)
            .withStopName("Stop 40")
            .withCoordinates(null)

    // ----- stops-away derivation -----

    @Test
    fun `stops away counts from the vehicle's next stop to the alight stop`() {
        // 09:02 — nothing served yet, next stop is the 1st, alight is the 4th → 4 away.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:02:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(4)
        assertThat(evaluation.etaUtc).isEqualTo(Instant.parse("2026-05-14T09:20:00Z"))
        assertThat(evaluation.hasRealTimeSignal).isTrue()
        assertThat(evaluation.fireApproachAlert).isFalse()
        assertThat(evaluation.fireArrivalAlert).isFalse()
    }

    @Test
    fun `stops away is zero once the alight stop's time has passed`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:22:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(0)
    }

    @Test
    fun `alight stop missing from the pattern reports unknown and stays quiet`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withStopId(999).build(),
                now = Instant.parse("2026-05-14T09:02:00Z"),
            )
        assertThat(evaluation.stopsAway).isNull()
        assertThat(evaluation.etaUtc).isNull()
        assertThat(evaluation.fireApproachAlert).isFalse()
        assertThat(evaluation.fireArrivalAlert).isFalse()
        assertThat(evaluation.nextCheckIn).isEqualTo(AlightAlertEvaluator.FAR_POLL)
    }

    // ----- approach stage (time-based) -----

    @Test
    fun `approach fires when the alight stop becomes the next stop`() {
        // 09:16 — 3rd stop (09:15) served, alight (09:20) is next.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:16:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(1)
        assertThat(evaluation.fireApproachAlert).isTrue()
        assertThat(evaluation.fireArrivalAlert).isFalse()
        assertThat(evaluation.updatedAlert.approachFired).isTrue()
        assertThat(evaluation.updatedAlert.arrivalFired).isFalse()
    }

    @Test
    fun `approach does not fire two stops out`() {
        // 09:11 — next stop is the 3rd, alight is the 4th → 2 away.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:11:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(2)
        assertThat(evaluation.fireApproachAlert).isFalse()
    }

    @Test
    fun `approach fires at most once`() {
        val first =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:16:00Z"),
            )
        assertThat(first.fireApproachAlert).isTrue()

        val second =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = first.updatedAlert,
                now = Instant.parse("2026-05-14T09:17:00Z"),
            )
        assertThat(second.fireApproachAlert).isFalse()
    }

    // ----- arrival stage (time-based) -----

    @Test
    fun `arrival fires inside the pre-arrival lead window`() {
        // 09:19:50 — 10 s before the 09:20 ETA, inside the 15 s lead.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:19:50Z"),
            )
        assertThat(evaluation.fireArrivalAlert).isTrue()
        assertThat(evaluation.updatedAlert.arrivalFired).isTrue()
    }

    @Test
    fun `arrival does not fire before the lead window opens`() {
        // 09:19:30 — 30 s out, window opens at 09:19:45.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:19:30Z"),
            )
        assertThat(evaluation.fireArrivalAlert).isFalse()
    }

    @Test
    fun `arrival still fires just after the eta but not once it is stale`() {
        val justAfter =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:20:30Z"),
            )
        assertThat(justAfter.fireArrivalAlert).isTrue()

        val stale =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:21:30Z"),
            )
        assertThat(stale.fireArrivalAlert).isFalse()
    }

    @Test
    fun `arrival fires at most once`() {
        val first =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:19:50Z"),
            )
        assertThat(first.fireArrivalAlert).isTrue()

        val second =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = first.updatedAlert,
                now = Instant.parse("2026-05-14T09:19:55Z"),
            )
        assertThat(second.fireArrivalAlert).isFalse()
    }

    @Test
    fun `arrival supersedes approach when both become due at once`() {
        // Armed very late: inside the lead window with neither stage fired. Only the arrival
        // alert sounds, but both latches advance so approach can't late-fire afterwards.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:19:50Z"),
            )
        assertThat(evaluation.fireArrivalAlert).isTrue()
        assertThat(evaluation.fireApproachAlert).isFalse()
        assertThat(evaluation.updatedAlert.approachFired).isTrue()
        assertThat(evaluation.updatedAlert.arrivalFired).isTrue()
    }

    // ----- re-arm semantics -----

    @Test
    fun `re-arming on a different stop resets both stages`() {
        // Ride past stop 40 with both stages fired, then re-arm on stop 50: the new alert's
        // fresh latches mean approach fires again when stop 50 becomes next.
        val fired = alertAtStop40().withApproachFired(true).withArrivalFired(true).build()
        val rearmed =
            AlightAlertMother.anAlightAlert()
                .withStopId(50)
                .withStopName("Stop 50")
                .withCoordinates(null)
                .build()
        assertThat(fired.approachFired).isTrue()

        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = rearmed,
                now = Instant.parse("2026-05-14T09:21:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(1)
        assertThat(evaluation.fireApproachAlert).isTrue()
    }

    // ----- schedule-only degradation -----

    @Test
    fun `no estimates and no location falls back to schedule and says so`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = false),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:16:00Z"),
            )
        assertThat(evaluation.hasRealTimeSignal).isFalse()
        assertThat(evaluation.usesGpsFallback).isFalse()
        assertThat(evaluation.isScheduleOnly).isTrue()
        // Scheduled times still drive the stages — degraded, not dead.
        assertThat(evaluation.fireApproachAlert).isTrue()
    }

    // ----- GPS fallback -----

    @Test
    fun `gps fallback fires approach once inside the previous-stop gap`() {
        val pattern = fiveStopPattern(estimated = false, withCoordinates = true)
        val alert = alertAtStop40().withCoordinates(coords(3)).build()

        // ~2 km south of the alight stop: outside the ~1 km previous-stop gap.
        val far =
            evaluator.evaluate(
                pattern = pattern,
                alert = alert,
                now = Instant.parse("2026-05-14T09:12:00Z"),
                location = coords(1),
            )
        assertThat(far.usesGpsFallback).isTrue()
        assertThat(far.isScheduleOnly).isFalse()
        assertThat(far.fireApproachAlert).isFalse()

        // ~500 m out: inside the gap.
        val near =
            evaluator.evaluate(
                pattern = pattern,
                alert = alert,
                now = Instant.parse("2026-05-14T09:12:00Z"),
                location = Coordinates(lat = -37.850 + 2.5 * 0.009, lng = 144.960),
            )
        assertThat(near.fireApproachAlert).isTrue()
        assertThat(near.fireArrivalAlert).isFalse()
    }

    @Test
    fun `gps fallback fires arrival within the arrival radius`() {
        val pattern = fiveStopPattern(estimated = false, withCoordinates = true)
        val alert = alertAtStop40().withCoordinates(coords(3)).withApproachFired(true).build()

        // ~50 m south of the alight stop.
        val evaluation =
            evaluator.evaluate(
                pattern = pattern,
                alert = alert,
                now = Instant.parse("2026-05-14T09:18:00Z"),
                location = Coordinates(lat = -37.850 + 3 * 0.009 - 0.00045, lng = 144.960),
            )
        assertThat(evaluation.fireArrivalAlert).isTrue()
    }

    @Test
    fun `gps fallback is ignored when real-time estimates exist`() {
        // Sitting right on top of the alight stop, but the pattern has estimates saying the
        // vehicle is stops away — trust the vehicle data, not the phone (the user may have
        // armed the alert while standing at their destination stop before boarding... or more
        // realistically, GPS drift shouldn't beat real-time).
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true, withCoordinates = true),
                alert = alertAtStop40().withCoordinates(coords(3)).build(),
                now = Instant.parse("2026-05-14T09:02:00Z"),
                location = coords(3),
            )
        assertThat(evaluation.usesGpsFallback).isFalse()
        assertThat(evaluation.fireApproachAlert).isFalse()
        assertThat(evaluation.fireArrivalAlert).isFalse()
    }

    @Test
    fun `coordinates are backfilled from the pattern onto the updated alert`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true, withCoordinates = true),
                alert = alertAtStop40().withCoordinates(null).build(),
                now = Instant.parse("2026-05-14T09:02:00Z"),
            )
        assertThat(evaluation.updatedAlert.coordinates).isEqualTo(coords(3))
    }

    // ----- adaptive cadence -----

    @Test
    fun `far from the alight stop polls at the far cadence`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:02:00Z"),
            )
        assertThat(evaluation.nextCheckIn).isEqualTo(AlightAlertEvaluator.FAR_POLL)
    }

    @Test
    fun `within two stops polls at the near cadence`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().build(),
                now = Instant.parse("2026-05-14T09:11:00Z"),
            )
        assertThat(evaluation.stopsAway).isEqualTo(2)
        assertThat(evaluation.nextCheckIn).isEqualTo(AlightAlertEvaluator.NEAR_POLL)
    }

    @Test
    fun `the last wake before the pre-arrival instant is exact`() {
        // 09:19:42 — the arrival alert is due at 09:19:45, 3 s away: sleep exactly 3 s rather
        // than a 5 s tick that would land 2 s late.
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).build(),
                now = Instant.parse("2026-05-14T09:19:42Z"),
            )
        assertThat(evaluation.nextCheckIn).isEqualTo(3.seconds)
    }

    @Test
    fun `after both stages fired the cadence relaxes`() {
        val evaluation =
            evaluator.evaluate(
                pattern = fiveStopPattern(estimated = true),
                alert = alertAtStop40().withApproachFired(true).withArrivalFired(true).build(),
                now = Instant.parse("2026-05-14T09:19:50Z"),
            )
        assertThat(evaluation.fireArrivalAlert).isFalse()
        // Still near by stops/ETA, so NEAR_POLL — but never the sub-5s exact wake again.
        assertThat(evaluation.nextCheckIn).isEqualTo(AlightAlertEvaluator.NEAR_POLL)
    }
}
