/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.network.model.SearchResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit-bound view of the Go proxy. The base URL is user-configurable (see
 * `SettingsRepository`), so the repository composes the absolute URL per call and hands it to
 * Retrofit via `@Url` rather than relying on the build-time base URL.
 *
 * The Retrofit `Retrofit.Builder().baseUrl(...)` is still required for client construction but
 * its value is a sentinel — every request supplies an absolute URL that overrides it.
 *
 * The PTV search endpoint accepts up to ~3 query parameters (`route_types`, `latitude`,
 * `longitude`) — Phase 02 only needs the simple form. Filters land alongside Nearby (Phase 05).
 *
 * Marked `internal` because Retrofit interfaces are an implementation detail of `:core:network`
 * — consumers see only repository interfaces in `:core:data`. Restricted-visibility on this
 * type is what keeps the layering from leaking.
 */
internal interface BackendApiService {
    @GET
    suspend fun searchStops(@Url url: String): SearchResponseDto
}
