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

import ac.jfx.openptv.core.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for [AppSettings]. The DataStore-backed implementation lives in `:app` for the
 * barebones cut — `:core:datastore` lands in the phase that needs typed preference DSL. Callers
 * see only this interface so the eventual relocation is a one-line dep change.
 */
interface SettingsRepository {
    /**
     * Current settings as a hot stream. Emits the latest persisted value on subscribe and on
     * every subsequent write. Used by the nav graph to decide whether to show the setup flow.
     */
    val settings: Flow<AppSettings>

    /** Update only the backend URL — used by the Settings screen post-onboarding. */
    suspend fun setBackendBaseUrl(url: String)

    /**
     * First-run completion: store the chosen URL and flip [AppSettings.setupCompleted] to true
     * in a single transaction so the nav graph never observes a half-finished setup.
     */
    suspend fun completeSetup(url: String)
}
