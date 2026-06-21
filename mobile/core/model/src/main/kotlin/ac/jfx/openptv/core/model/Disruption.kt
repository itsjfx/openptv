package ac.jfx.openptv.core.model

/**
 * A PTV service disruption — planned works, a closure, minor delays, and so on. PTV sideloads
 * these on the departures endpoint (`expand=Disruption`) keyed by id; the mapper joins each
 * departure's `disruption_ids` back to the full record so the UI can show the real title and
 * description on tap rather than the old "a disruption affects this route" placeholder.
 *
 * `type` is PTV's short category ("Planned Closure", "Minor Delays"). `description` is the longer
 * body it writes for the live-travel-updates article. `url` points at that public article and is
 * nullable because a few records omit it. `colour` is PTV's hex severity tint (amber for planned
 * works, etc.) — kept verbatim so the UI can accent the indicator, null when PTV doesn't send one.
 */
data class Disruption(
    val id: DisruptionId,
    val title: String,
    val description: String,
    val type: String,
    val url: String?,
    val colour: String?,
)

@JvmInline
value class DisruptionId(val value: Long)
