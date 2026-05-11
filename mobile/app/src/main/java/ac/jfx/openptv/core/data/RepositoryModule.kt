package ac.jfx.openptv.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires repository interfaces to their default impls. Hilt prefers `@Binds` over `@Provides`
 * for plain interface-to-impl pairs because it generates less code at compile time.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {
    @Binds
    @Singleton
    internal abstract fun bindStopSearchRepository(
        impl: StopSearchRepositoryImpl,
    ): StopSearchRepository
}
