package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.DeviceHeadingProvider
import ac.jfx.openptv.core.common.LocationProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the device-sensor seams ([LocationProvider], [DeviceHeadingProvider]) to their default
 * implementations. Lives in a separate Hilt module from [DataModule] so `:core:data-test`'s
 * `FakeLocationModule` can swap *only* these bindings via `@TestInstallIn(replaces =
 * [LocationModule::class])`, leaving the repository fakes in `FakeDataModule` independent. Same
 * pattern NIA uses to split data-layer test seams.
 *
 * Heading rides in the same module as location because the two are paired at the call site (the
 * nearby map's "blue dot + cone" — issue #99) and share the same lifecycle gating: both stop
 * emitting when the screen pauses, both depend on permission/sensor availability checks the
 * impls absorb internally.
 *
 * Public (not internal) so the `replaces = [...]` reference compiles from the test module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    @Singleton
    internal abstract fun bindLocationProvider(impl: LocationManagerLocationProvider): LocationProvider

    @Binds
    @Singleton
    internal abstract fun bindDeviceHeadingProvider(impl: SensorManagerDeviceHeadingProvider): DeviceHeadingProvider
}
