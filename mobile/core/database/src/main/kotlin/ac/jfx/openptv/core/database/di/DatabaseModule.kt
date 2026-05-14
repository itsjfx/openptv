package ac.jfx.openptv.core.database.di

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.dao.FavouriteRouteAtStopDao
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt graph for the persistence layer. Provides the single [OpenPtvDatabase] instance and
 * exposes each DAO so consumers (`:core:data`) inject only the surface they actually need.
 *
 * The builder deliberately omits `.fallbackToDestructiveMigration()` — the widget (Phase 7) and
 * notifications (Phase 8) both depend on data living through schema bumps, so any future schema
 * change must ship a real `Migration`. Letting Room nuke the DB on a missing migration would
 * silently delete every favourite the user has starred, which we'd never spot in code review.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideOpenPtvDatabase(
        @ApplicationContext context: Context,
    ): OpenPtvDatabase =
        Room.databaseBuilder(
            context = context,
            klass = OpenPtvDatabase::class.java,
            name = OpenPtvDatabase.DATABASE_NAME,
        ).build()

    @Provides
    fun provideFavouriteRouteAtStopDao(database: OpenPtvDatabase): FavouriteRouteAtStopDao =
        database.favouriteRouteAtStopDao()
}
