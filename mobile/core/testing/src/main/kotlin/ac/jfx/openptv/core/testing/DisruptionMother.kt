package ac.jfx.openptv.core.testing

import ac.jfx.openptv.core.model.Disruption
import ac.jfx.openptv.core.model.DisruptionId

/**
 * Object Mother for [Disruption] test fixtures. Calling `.build()` with no overrides yields the
 * canonical Jessie St/Bell St closure (issue #177) — a planned route 903/906 stop closure with a
 * PTV article URL and the amber planned-works tint. Chain `with*` to override only what a test cares
 * about.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
class DisruptionMother private constructor() {
    companion object {
        private const val DEFAULT_ID = 364837L
        private const val DEFAULT_TITLE =
            "Route 903 & 906: Temporary bus stop closures from Friday 19 June to Sunday 21 June 2026"
        private const val DEFAULT_DESCRIPTION =
            "From 10pm Friday 19 June to last service Sunday 21 June, some stops are temporarily closed."
        private const val DEFAULT_TYPE = "Planned Closure"
        private const val DEFAULT_URL =
            "http://ptv.vic.gov.au/live-travel-updates/article/route-903-and-906-temporary-bus-stop-closures"
        private const val DEFAULT_COLOUR = "#ffd500"

        fun aDisruption(): DisruptionBuilder = DisruptionBuilder()
    }

    class DisruptionBuilder {
        private var id: Long = DEFAULT_ID
        private var title: String = DEFAULT_TITLE
        private var description: String = DEFAULT_DESCRIPTION
        private var type: String = DEFAULT_TYPE
        private var url: String? = DEFAULT_URL
        private var colour: String? = DEFAULT_COLOUR

        fun withId(value: Long) = apply { this.id = value }

        fun withTitle(value: String) = apply { this.title = value }

        fun withDescription(value: String) = apply { this.description = value }

        fun withType(value: String) = apply { this.type = value }

        fun withUrl(value: String?) = apply { this.url = value }

        fun withColour(value: String?) = apply { this.colour = value }

        fun build(): Disruption =
            Disruption(
                id = DisruptionId(id),
                title = title,
                description = description,
                type = type,
                url = url,
                colour = colour,
            )
    }
}
