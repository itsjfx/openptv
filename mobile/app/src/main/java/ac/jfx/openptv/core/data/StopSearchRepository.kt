package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop

/**
 * Search-facing slice of the stop repository. Domain layer (ViewModels / use cases) sees only
 * this interface; the Retrofit-backed impl is wired by Hilt via [RepositoryModule].
 *
 * Errors are folded into [Result.Error] rather than thrown, so callers never need a try/catch
 * — they pattern-match the result and map each branch onto a UI state.
 */
interface StopSearchRepository {
    suspend fun searchStops(term: String): Result<List<Stop>>
}
