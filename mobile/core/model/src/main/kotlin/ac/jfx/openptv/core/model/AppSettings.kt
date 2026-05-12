/*
 * Copyright 2026 OpenPTV contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ac.jfx.openptv.core.model

/**
 * Persisted app settings. Domain type with no Android deps — promoted to `:core:model`
 * alongside the multi-module split.
 *
 * - [backendBaseUrl] is the user-chosen proxy URL. It MUST end with a trailing slash because
 *   Retrofit's `@Url` resolves relative paths against it (`baseUrl + "search/$term"`).
 * - [setupCompleted] gates the network layer: until the user has explicitly chosen a server
 *   and accepted, no request leaves the device.
 */
data class AppSettings(
    val backendBaseUrl: String,
    val setupCompleted: Boolean,
)
