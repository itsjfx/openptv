package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.network.BackendUrlProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires repository interfaces to their default impls. Hilt prefers `@Binds` over `@Provides`
 * for plain interface-to-impl pairs because it generates less code at compile time.
 *
 * Bindings owned here:
 * - `StopSearchRepository` -> `StopSearchRepositoryImpl` (impl lives in this module).
 * - `BackendUrlProvider` -> `SettingsBackendUrlProvider` (impl reads `SettingsRepository`,
 *   which lives in this module too — `:core:network` only declares the interface).
 *
 * Not owned here: `SettingsRepository` itself. That binding is wired by `:app` alongside the
 * DataStore-backed impl until `:core:datastore` lands (issue #11).
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

    @Binds
    @Singleton
    internal abstract fun bindBackendUrlProvider(
        impl: SettingsBackendUrlProvider,
    ): BackendUrlProvider
}
