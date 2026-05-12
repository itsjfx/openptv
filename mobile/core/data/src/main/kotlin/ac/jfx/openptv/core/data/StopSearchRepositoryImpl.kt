/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.StopSearchDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Default impl. Delegates wire-level concerns to [StopSearchDataSource] (which knows about
 * Retrofit/OkHttp) and maps domain failures into [Result.Error].
 *
 * The backend URL is read from [SettingsRepository] per call so a Settings-screen edit takes
 * effect on the very next search without restarting the app or rebuilding the Retrofit graph.
 *
 * Cancellation must propagate (otherwise a stale coroutine ignores its parent being torn down),
 * so [CancellationException] is rethrown rather than swallowed into [Result.Error] — the
 * conventional shape for catch-all blocks in coroutines.
 */
internal class StopSearchRepositoryImpl @Inject constructor(
    private val dataSource: StopSearchDataSource,
    private val settings: SettingsRepository,
) : StopSearchRepository {
    // Repository boundary: any non-cancellation failure (IO, parse, JSON, ...) becomes
    // `Result.Error` so callers don't have to know the underlying type lattice. Catching
    // `Throwable` is the conventional shape; see KDoc above for the cancellation contract.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun searchStops(term: String): Result<List<Stop>> = try {
        val baseUrl = settings.settings.first().backendBaseUrl
        Result.Success(dataSource.searchStops(baseUrl, term))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        Result.Error(t)
    }
}
