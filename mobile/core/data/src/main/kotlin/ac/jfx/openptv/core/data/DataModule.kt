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

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires repository interfaces to their default impls. Hilt prefers `@Binds` over `@Provides`
 * for plain interface-to-impl pairs because it generates less code at compile time.
 *
 * Note: only repositories whose impl lives in `:core:data` are bound here. [SettingsRepository]
 * is bound by `:app` (alongside the DataStore-backed impl) until `:core:datastore` lands.
 *
 * `:core:data-test` swaps this whole module out via `@TestInstallIn(replaces = [DataModule::class])`,
 * so the public `abstract class` declaration is part of the test seam: changing the class name
 * here means updating the `replaces = [...]` reference in the test module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    @Singleton
    internal abstract fun bindStopSearchRepository(
        impl: StopSearchRepositoryImpl,
    ): StopSearchRepository
}
