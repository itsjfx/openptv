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

/**
 * Network-layer search seam. Public because `:core:data` injects it, but the only thing exposed
 * is the mapped domain type — Retrofit DTOs stay `internal` to this module. That keeps the
 * dependency rule honest: `:core:data` (and anything downstream) never imports `:core:network`'s
 * wire types, only its public abstractions.
 *
 * The data source is the boundary between "I know about HTTP" and "I know about repositories":
 * if a future phase swaps Retrofit for Ktor, only the impl behind this interface changes.
 *
 * - [baseUrl] is the user-configured proxy URL (trailing slash). The data source composes the
 *   absolute URL per call rather than relying on a build-time `baseUrl` because the user can
 *   change it at runtime via Settings.
 * - [term] is the raw query string from the user. The data source URL-encodes it.
 */
interface StopSearchDataSource {
    suspend fun searchStops(baseUrl: String, term: String): List<Stop>
}
