package ac.jfx.openptv.core.network.model

/**
 * Object Mother for the internal [DisruptionDto]. Lives next to the DTO it builds because
 * [DisruptionDto] is `internal` — only same-module mapper tests construct it. Defaults to the
 * canonical route 903/906 closure (issue #177) so failure dumps are recognisable.
 *
 * See `~/.claude/skills/object-mother/skill.md` for the pattern spec.
 */
internal class DisruptionDtoMother private constructor() {
    companion object {
        private const val DEFAULT_ID = 364837L
        private const val DEFAULT_TITLE = "Route 903 & 906: Temporary bus stop closures"
        private const val DEFAULT_DESCRIPTION = "Some stops are temporarily closed this weekend."
        private const val DEFAULT_TYPE = "Planned Closure"
        private const val DEFAULT_URL = "http://ptv.vic.gov.au/live-travel-updates/article/route-903-906"
        private const val DEFAULT_COLOUR = "#ffd500"

        internal fun aDisruptionDto(): DisruptionDtoBuilder = DisruptionDtoBuilder()
    }

    internal class DisruptionDtoBuilder {
        private var disruptionId: Long = DEFAULT_ID
        private var title: String = DEFAULT_TITLE
        private var description: String = DEFAULT_DESCRIPTION
        private var disruptionType: String = DEFAULT_TYPE
        private var url: String? = DEFAULT_URL
        private var colour: String? = DEFAULT_COLOUR

        fun withDisruptionId(value: Long) = apply { this.disruptionId = value }

        fun withTitle(value: String) = apply { this.title = value }

        fun withDescription(value: String) = apply { this.description = value }

        fun withDisruptionType(value: String) = apply { this.disruptionType = value }

        fun withUrl(value: String?) = apply { this.url = value }

        fun withColour(value: String?) = apply { this.colour = value }

        fun build(): DisruptionDto =
            DisruptionDto(
                disruptionId = disruptionId,
                title = title,
                description = description,
                disruptionType = disruptionType,
                url = url,
                colour = colour,
            )
    }
}
