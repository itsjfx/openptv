package ac.jfx.openptv

import ac.jfx.openptv.core.database.converter.RouteTypeConverter
import ac.jfx.openptv.core.database.dao.FavouriteDestinationAtStopDao
import ac.jfx.openptv.core.database.entity.FavouriteDestinationAtStopEntity
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single Room database for the app. Lives at the top-level `ac.jfx.openptv` package (rather
 * than under `ac.jfx.openptv.core.database`) so the schema export path —
 * `core/database/schemas/ac.jfx.openptv.OpenPtvDatabase/<version>.json` — stays short and
 * stable across the multi-module re-layout.
 *
 * v1 — per-route favourites (`favourite_routes_at_stop`, keyed `(stopId, routeId, directionId)`).
 * v2 — per-destination favourites (`favourite_destinations_at_stop`, keyed `(stopId, destinationKey)`).
 * Migration in `core.database.migration.MIGRATION_1_2` collapses by `LOWER(directionName)` per stop.
 *
 * `exportSchema = true` so Room writes `<version>.json` under the configured schema directory at
 * build time. JSONs are committed; the migration test uses them as the source of truth.
 */
@Database(
    entities = [FavouriteDestinationAtStopEntity::class],
    version = OpenPtvDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(RouteTypeConverter::class)
abstract class OpenPtvDatabase : RoomDatabase() {
    abstract fun favouriteDestinationAtStopDao(): FavouriteDestinationAtStopDao

    companion object {
        const val VERSION: Int = 2

        /**
         * Filename used by `Room.databaseBuilder`. Centralised so the migration test harness
         * can open the same on-disk file the production app uses.
         */
        const val DATABASE_NAME: String = "openptv.db"
    }
}
