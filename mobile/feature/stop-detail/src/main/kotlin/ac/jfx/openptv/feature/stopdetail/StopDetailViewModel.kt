package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.domain.GetStopDetailUseCase
import ac.jfx.openptv.core.domain.ObserveDeparturesUseCase
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import retrofit2.HttpException
import java.io.IOException

/**
 * Stop-detail ViewModel. Owns three things:
 *
 *  1. A one-shot header fetch on init ([loadHeader]). Re-runs on retry.
 *  2. A 30 s polling Flow of departures, kicked off whenever the screen enters
 *     [androidx.lifecycle.Lifecycle.State.RESUMED] and cancelled when it leaves. The UI driver is
 *     [startObserving] / [stopObserving]; the Compose layer wraps these in `repeatOnLifecycle`.
 *  3. Pull-to-refresh, which forces a new collection cycle ([refresh]).
 *
 * `Clock` and `RelativeTimeFormatter` come from Hilt's `SingletonComponent`; `stopId` and
 * `routeType` are assisted so the Compose layer can hand the destination key into the ViewModel
 * factory at navigate-time without round-tripping through `SavedStateHandle` (Navigation 3 alpha
 * doesn't wire NavKey fields into the saved state automatically the way Navigation 2 does).
 *
 * Error handling: an error mid-poll surfaces as `DeparturesState.Error` but the loop is *not*
 * broken — the underlying repository keeps ticking, so the next 30 s emission can recover. This
 * mirrors the contract spelled out in [`ac.jfx.openptv.core.data.DepartureRepository`].
 */
@HiltViewModel(assistedFactory = StopDetailViewModel.Factory::class)
class StopDetailViewModel
    @AssistedInject
    constructor(
        @Assisted("stopId") private val stopIdValue: Int,
        @Assisted("routeTypeCode") private val routeTypeCode: Int,
        private val getStopDetail: GetStopDetailUseCase,
        private val observeDepartures: ObserveDeparturesUseCase,
        private val clock: Clock,
        /**
         * Exposed for the Compose layer so the screen renders relative times under the same
         * injected clock the ViewModel uses for `asOf`. `internal` so previews / tests can read it.
         */
        internal val timeFormatter: RelativeTimeFormatter,
    ) : ViewModel() {
        private val stopId: StopId = StopId(stopIdValue)
        private val routeType: RouteType = RouteType.fromCode(routeTypeCode)

        /**
         * Assisted-injection factory. Takes raw `Int`s rather than the domain value classes
         * ([StopId], [RouteType]) because Dagger's assisted-inject codegen doesn't currently
         * deal with the mangled JVM names that Kotlin value classes use as method parameters
         * (the symptom is `not a valid name: create-…` at KSP time). Boxing to the value class
         * happens at the ViewModel boundary instead — same effect, no name-mangling.
         */
        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("stopId") stopId: Int,
                @Assisted("routeTypeCode") routeTypeCode: Int,
            ): StopDetailViewModel
        }

        private val _uiState = MutableStateFlow(StopDetailUiState.Initial)
        val uiState: StateFlow<StopDetailUiState> = _uiState.asStateFlow()

        /** Tracks the active observation coroutine so `startObserving` is idempotent. */
        private var observeJob: Job? = null

        init {
            loadHeader()
        }

        /**
         * Kick off (or re-kick) the polling collection of [observeDepartures]. Called from the UI
         * inside a `repeatOnLifecycle(Lifecycle.State.RESUMED)` block, so it runs on every
         * Pause→Resume cycle. Idempotent — re-entry while a previous job is still active cancels
         * the previous one (mirrors the "fresh collector lifetime drives polling" contract).
         */
        fun startObserving() {
            observeJob?.cancel()
            observeJob =
                viewModelScope.launch {
                    observeDepartures(stopId, routeType).collect { result ->
                        _uiState.update { current -> current.applyDepartureResult(result) }
                    }
                }
        }

        fun stopObserving() {
            observeJob?.cancel()
            observeJob = null
        }

        /**
         * Pull-to-refresh handler. Cancels the active collector (the polling Flow is "hot" only
         * for as long as a collector is attached) and re-subscribes, which forces a fresh fetch.
         * Flips `isRefreshing` true; the next emission clears it.
         */
        fun refresh() {
            _uiState.update { it.copy(isRefreshing = true) }
            startObserving()
        }

        fun retryHeader() {
            _uiState.update { it.copy(header = HeaderState.Loading) }
            loadHeader()
        }

        private fun loadHeader() {
            viewModelScope.launch {
                val result: Result<StopDetail> = getStopDetail(stopId, routeType)
                _uiState.update { current ->
                    current.copy(
                        header =
                            when (result) {
                                is Result.Loading -> HeaderState.Loading
                                is Result.Success -> HeaderState.Loaded(result.data)
                                is Result.Error -> HeaderState.Error(result.throwable.toUserFacingReason())
                            },
                    )
                }
            }
        }

        private fun StopDetailUiState.applyDepartureResult(result: Result<List<Departure>>): StopDetailUiState =
            when (result) {
                is Result.Loading -> copy(departures = DeparturesState.Loading)
                is Result.Success -> {
                    val groups = result.data.toGroupedList(currentHeader = header)
                    copy(
                        departures =
                            if (groups.isEmpty()) DeparturesState.Empty else DeparturesState.Loaded(groups),
                        isRefreshing = false,
                        asOf = clock.now(),
                    )
                }
                is Result.Error ->
                    copy(
                        departures = DeparturesState.Error(result.throwable.toUserFacingReason()),
                        isRefreshing = false,
                    )
            }

        /**
         * Group departures by (routeId, directionId), preserving insertion order within each
         * group. The header label uses the [Route] from the header payload when available; that's
         * what gives us "Route 19 · North Coburg" rather than "Route #19 · …". Groups are sorted
         * by the earliest departure in each group so the closest service surfaces at the top —
         * the exact "newer ones appear smoothly" criterion in the issue.
         */
        private fun List<Departure>.toGroupedList(currentHeader: HeaderState): List<Group> {
            val servingRoutes =
                when (currentHeader) {
                    is HeaderState.Loaded -> currentHeader.detail.servingRoutes.associateBy { it.id.value }
                    else -> emptyMap()
                }
            return groupBy { GroupKey(it.routeId.value, it.direction.id.value) }
                .map { (key, departures) ->
                    val route = servingRoutes[key.routeId]
                    val routeNumber = route?.number?.ifBlank { route.name }.orEmpty().ifBlank { "#${key.routeId}" }
                    Group(
                        key = key,
                        route = route,
                        routeType = route?.routeType ?: routeType,
                        headerLabel = "Route $routeNumber · ${departures.first().direction.name}",
                        departures = departures.sortedBy { it.effectiveDepartureUtc() },
                    )
                }
                .sortedBy { it.departures.first().effectiveDepartureUtc() }
        }

        private fun Throwable.toUserFacingReason(): String =
            when (this) {
                is HttpException ->
                    when (code()) {
                        in HTTP_CLIENT_ERROR_RANGE -> "Stop request was rejected (${code()})."
                        in HTTP_SERVER_ERROR_RANGE -> "The proxy is having a bad time (${code()}). Try again."
                        else -> "Unexpected HTTP error (${code()})."
                    }
                is IOException -> "Couldn't reach the network. Check your connection."
                is kotlinx.serialization.SerializationException ->
                    "Response was malformed. The backend may be out of date."
                else -> message ?: "Something went wrong."
            }

        private companion object {
            private val HTTP_CLIENT_ERROR_RANGE = 400..499
            private val HTTP_SERVER_ERROR_RANGE = 500..599
        }
    }

/** Best-known departure instant — real-time prediction wins, falls back to the timetable. */
internal fun Departure.effectiveDepartureUtc() = estimatedDepartureUtc ?: scheduledDepartureUtc
