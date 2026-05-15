package ac.jfx.openptv.feature.settings

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.data.test.FakeSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AndroidTest-only Hilt module that binds [SettingsRepository] to the in-memory fake from
 * `:core:data-test`. The production binding lives in `:app`'s `AppDataModule`, which isn't on
 * the test classpath for `:feature:settings` androidTests — so `@TestInstallIn(replaces = ...)`
 * isn't appropriate here. A plain `@InstallIn(SingletonComponent::class)` provides the missing
 * binding without trying to replace anything.
 *
 * Lives in the androidTest source set rather than `:core:data-test`'s shared module
 * (`FakeDataModule`) for the same reason `FakeDataModule` deliberately omits this binding: the
 * prod binding's home (`:app`) varies per consumer, and pinning the fake module to one
 * SingletonComponent shape would conflict with `:app`'s androidTests once they land.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class FakeSettingsRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: FakeSettingsRepository): SettingsRepository
}
