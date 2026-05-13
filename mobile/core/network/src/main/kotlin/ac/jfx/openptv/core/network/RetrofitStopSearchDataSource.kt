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

import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.network.model.toDomain
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Retrofit-backed [StopSearchDataSource]. Lives in `:core:network` because that's the only place
 * `BackendApiService` (also internal) is visible.
 *
 * The base URL comes from injected [BackendUrlProvider] (production impl reads from
 * `SettingsRepository` in `:core:data`; tests pass a lambda). Reading per call means the user's
 * URL change on the Settings screen is honoured on the very next request — no app restart, no
 * Retrofit rebuild.
 *
 * URL-encoding the search term protects against terms containing `/` or `?` reaching the wire
 * untouched and against a misbehaving terminal-comma-on-end.
 */
internal class RetrofitStopSearchDataSource @Inject constructor(
    private val api: BackendApiService,
    private val backendUrl: BackendUrlProvider,
) : StopSearchDataSource {
    override suspend fun searchStops(term: String): List<Stop> {
        val baseUrl = backendUrl.backendBaseUrl()
        val encodedTerm = URLEncoder.encode(term, Charsets.UTF_8.name())
        return api.searchStops("${baseUrl}search/$encodedTerm").toDomain()
    }
}
