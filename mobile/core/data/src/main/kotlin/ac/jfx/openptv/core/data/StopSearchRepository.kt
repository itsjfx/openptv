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

/**
 * Search-facing slice of the stop repository. Domain layer (ViewModels / use cases) sees only
 * this interface; the network-backed impl is wired by Hilt via [DataModule].
 *
 * Errors are folded into [Result.Error] rather than thrown, so callers never need a try/catch
 * — they pattern-match the result and map each branch onto a UI state.
 */
interface StopSearchRepository {
    suspend fun searchStops(term: String): Result<List<Stop>>
}
