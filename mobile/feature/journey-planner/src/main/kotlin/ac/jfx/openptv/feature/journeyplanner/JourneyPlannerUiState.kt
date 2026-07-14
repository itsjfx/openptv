package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.Stop
import kotlinx.datetime.Instant

/** Which endpoint the inline stop picker is currently choosing. */
enum class JourneyField { Origin, Destination }

/**
 * The inline stop picker's own little state machine — a clone of `:feature:search`'s
 * `SearchUiState` because the picker *is* that screen's pipeline embedded in this one. Kept
 * separate (not reused across modules) so the two features stay independent per the module
 * rules; the shape is four states and not worth a shared module.
 *
 * [Idle] (empty query) carries the user's favourite stops (issue #209) — derived from the
 * destination-at-stop favourites, distinct by stop — so common endpoints are one tap away
 * before any typing. Empty list falls back to the "type a stop name" hint.
 */
sealed interface StopPickerState {
    data class Idle(val favouriteStops: List<Stop> = emptyList()) : StopPickerState

    data object Loading : StopPickerState

    data object Empty : StopPickerState

    data class Results(val stops: List<Stop>) : StopPickerState

    data class Error(val reason: String) : StopPickerState
}

/**
 * The journey results area. [Idle] until both endpoints are chosen; [NoDirectServices] is the
 * deliberate empty state — no route serves both stops (including cross-mode picks) or nothing
 * boardable runs in the window. Direct services only: the PTV API can't answer multi-leg, so
 * the empty state says so instead of pretending to try.
 */
sealed interface JourneyResultsState {
    data object Idle : JourneyResultsState

    data object Loading : JourneyResultsState

    data class Loaded(val options: List<JourneyOption>) : JourneyResultsState

    data object NoDirectServices : JourneyResultsState

    data class Error(val reason: String) : JourneyResultsState
}

/**
 * Single UiState for the journey planner screen (issue #204). `query` is the raw, undebounced
 * picker text so the text field echoes keystrokes instantly; the debounced search pipeline
 * feeds [picker] separately. `selectedTime` null means "departing now" — the results poll live;
 * non-null pins a static snapshot, mirroring stop-detail's custom-time behaviour.
 *
 * `isFavouriteJourney` (issue #209) is the ★ toggle's state for the current (origin,
 * destination) pair; always false while either endpoint is missing (the star isn't rendered
 * then).
 *
 * `routeTypeFilter` (issue #213) is the picker's mode-chip selection — empty means "all modes".
 * It scopes both the search results (via PTV's `route_types` parameter) and the favourite-stops
 * idle list (client-side). Session-scoped, not persisted.
 *
 * `alightArmedRunRef` (issue #220) is the run whose result-row bell renders armed: the followed
 * trip's run when its alight alert targets the *current* destination, null otherwise. Derived
 * reactively from the followed-trip repository, so arming/disarming on the run-pattern screen
 * keeps the planner's bells honest. `followReplaceCandidate` mirrors run-pattern's
 * replace-confirmation (issue #200); `alightLocationPromptNeeded` mirrors its contextual
 * location request for schedule-only services (issue #201).
 */
data class JourneyPlannerUiState(
    val origin: Stop? = null,
    val destination: Stop? = null,
    val selectedTime: Instant? = null,
    val activeField: JourneyField? = null,
    val query: String = "",
    val routeTypeFilter: Set<RouteType> = emptySet(),
    val picker: StopPickerState = StopPickerState.Idle(),
    val results: JourneyResultsState = JourneyResultsState.Idle,
    val isFavouriteJourney: Boolean = false,
    val alightArmedRunRef: RunRef? = null,
    val followReplaceCandidate: FollowedTrip? = null,
    val alightLocationPromptNeeded: Boolean = false,
)
