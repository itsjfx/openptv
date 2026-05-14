package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.StopDetailDataSource
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Default impl: delegates the HTTP fetch to [StopDetailDataSource] and converts wire-level
 * outcomes into [Result]. Mirrors [StopSearchRepositoryImpl] for cancellation handling — the
 * convention is documented there.
 *
 * A `null` response from the data source (PTV returned no stop block) is surfaced as a
 * [Result.Error] carrying [StopNotFoundException]. The screen-level UiState can render a
 * specific "stop not found" empty state from that signal without having to inspect the
 * underlying HTTP code.
 */
internal class StopDetailRepositoryImpl
    @Inject
    constructor(
        private val dataSource: StopDetailDataSource,
    ) : StopDetailRepository {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun getStopDetail(
            stopId: StopId,
            routeType: RouteType,
        ): Result<StopDetail> =
            try {
                val detail = dataSource.getStopDetail(stopId, routeType)
                if (detail == null) {
                    Result.Error(StopNotFoundException(stopId))
                } else {
                    Result.Success(detail)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }
    }

/**
 * Thrown when PTV responds OK but with no `stop` block — the screen treats this differently
 * from a transport-level failure. ViewModel maps it onto a user-facing "Stop not found" state.
 */
class StopNotFoundException(
    stopId: StopId,
) : NoSuchElementException("No stop with id ${stopId.value}")
