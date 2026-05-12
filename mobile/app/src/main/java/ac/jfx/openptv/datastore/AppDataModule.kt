package ac.jfx.openptv.datastore

import ac.jfx.openptv.core.data.SettingsRepository
import ac.jfx.openptv.core.datastore.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * App-local binding for [SettingsRepository]. This sits in `:app` rather than `:core:data`
 * because the implementation is DataStore-backed and `:core:datastore` doesn't exist yet —
 * issue #11 puts `:core:datastore` explicitly out of scope. When that module lands, this
 * module migrates there and `:app` loses one more `@Module` it shouldn't have owned in the
 * first place.
 *
 * `SettingsRepository`'s production-module binding is here AND its `@TestInstallIn` fake
 * binding will need to live in `:app/src/androidTest/` (or, once `:core:datastore` exists,
 * in `:core:data-test`). For now `:core:data-test`'s `FakeDataModule` only replaces the
 * `:core:data` `DataModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppDataModule {
    @Binds
    @Singleton
    internal abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl,
    ): SettingsRepository
}
