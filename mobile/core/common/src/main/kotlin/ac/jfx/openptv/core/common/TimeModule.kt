package ac.jfx.openptv.core.common

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.datetime.Clock
import javax.inject.Singleton

/**
 * Binds `Clock.System` for production. Tests construct [RelativeTimeFormatter] (and any other
 * `Clock` consumer) with a fixed clock directly, or `@BindValue` a fake — never go through this
 * module.
 *
 * Singleton because `Clock.System` is a stateless singleton itself; one Hilt binding is enough.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object TimeModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.System
}
