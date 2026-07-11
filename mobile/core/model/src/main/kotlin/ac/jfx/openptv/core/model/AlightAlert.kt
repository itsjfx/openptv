package ac.jfx.openptv.core.model

/**
 * The armed "I'm getting off here" alert on a followed trip (issue #201). Carries the alight
 * stop's identity plus everything the background alert service needs when the app process is
 * gone: the display name for notification copy and the stop's [coordinates] for the GPS
 * proximity fallback (trams return no real-time estimates on the pattern endpoint — see
 * CLAUDE.md quirks).
 *
 * `coordinates` is nullable because the pattern's stop sideload omits geo fields; the data
 * layer's `stops/route` join usually fills them in, but a stop PTV never located simply can't
 * drive the GPS fallback (time-based stages still work).
 *
 * [approachFired] / [arrivalFired] are the fire-once latches for the two alert stages
 * ("1 stop before" and "~10-15 s before arrival"). They are persisted with the trip so a
 * service restart doesn't re-fire a stage; changing the alight stop replaces the whole
 * [AlightAlert], which resets both latches — that's the re-arm semantic.
 */
data class AlightAlert(
    val stopId: StopId,
    val stopName: String,
    val coordinates: Coordinates?,
    val approachFired: Boolean = false,
    val arrivalFired: Boolean = false,
)
