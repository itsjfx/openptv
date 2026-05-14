package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the [LocationProvider] interface to its default implementation. Lives in a separate Hilt
 * module from [DataModule] so `:core:data-test`'s `FakeLocationModule` can swap *only* this
 * binding via `@TestInstallIn(replaces = [LocationModule::class])`, leaving the repository
 * fakes in `FakeDataModule` independent. Same pattern NIA uses to split data-layer test seams.
 *
 * Public (not internal) so the `replaces = [...]` reference compiles from the test module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    @Singleton
    internal abstract fun bindLocationProvider(impl: LocationManagerLocationProvider): LocationProvider
}
