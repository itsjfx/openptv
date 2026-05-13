package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.DataModule
import ac.jfx.openptv.core.data.DepartureRepository
import ac.jfx.openptv.core.data.StopDetailRepository
import ac.jfx.openptv.core.data.StopSearchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the production `DataModule` for every Hilt instrumented test that depends on this
 * module. Future feature androidTests just declare
 * `androidTestImplementation(project(":core:data-test"))` and inherit these fakes —
 * no per-test `@UninstallModules` boilerplate.
 *
 * `SettingsRepository` is intentionally absent: its production binding is wired by `:app`
 * alongside the DataStore impl, so the test seam for it lives in a `@TestInstallIn` module in
 * `:app/src/androidTest/` (created in the phase that adds the first androidTest). When
 * `:core:datastore` lands and the binding moves there, the matching `@TestInstallIn` moves into
 * this module too.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class],
)
abstract class FakeDataModule {
    @Binds
    @Singleton
    internal abstract fun bindStopSearchRepository(
        impl: FakeStopSearchRepository,
    ): StopSearchRepository

    @Binds
    @Singleton
    internal abstract fun bindStopDetailRepository(
        impl: FakeStopDetailRepository,
    ): StopDetailRepository

    @Binds
    @Singleton
    internal abstract fun bindDepartureRepository(
        impl: FakeDepartureRepository,
    ): DepartureRepository
}
