package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.common.LocationProvider
import ac.jfx.openptv.core.data.LocationModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces the production `LocationModule` for every Hilt instrumented test that depends on
 * `:core:data-test`, swapping `LocationManagerLocationProvider` for [FakeLocationProvider].
 *
 * Kept separate from [FakeDataModule] because `LocationModule` is a separate production module —
 * `@TestInstallIn(replaces = [...])` works one module at a time, and splitting the test seams
 * lets future tests opt out of the location fake (via `@UninstallModules(FakeLocationModule::class)`)
 * without losing the data-layer fakes.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [LocationModule::class],
)
abstract class FakeLocationModule {
    @Binds
    @Singleton
    internal abstract fun bindLocationProvider(impl: FakeLocationProvider): LocationProvider
}
