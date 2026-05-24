package ac.jfx.openptv.core.database.di

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.dao.FavouriteDestinationAtStopDao
import ac.jfx.openptv.core.database.migration.MIGRATION_1_2
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
 * The builder deliberately omits `.fallbackToDestructiveMigration()` — any schema change must
 * ship a real `Migration`. Letting Room nuke the DB on a missing migration would silently delete
 * every favourite the user has starred.
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
        )
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideFavouriteDestinationAtStopDao(database: OpenPtvDatabase): FavouriteDestinationAtStopDao =
        database.favouriteDestinationAtStopDao()
}
