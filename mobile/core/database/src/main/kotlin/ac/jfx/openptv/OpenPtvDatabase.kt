package ac.jfx.openptv

import ac.jfx.openptv.core.database.converter.RouteTypeConverter
import ac.jfx.openptv.core.database.dao.FavouriteRouteAtStopDao
import ac.jfx.openptv.core.database.entity.FavouriteRouteAtStopEntity
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The single Room database for the app. Lives at the top-level `ac.jfx.openptv` package (rather
 * than under `ac.jfx.openptv.core.database`) so the schema export path —
 * `core/database/schemas/ac.jfx.openptv.OpenPtvDatabase/<version>.json` — stays short and
 * stable across the multi-module re-layout. The schema directory tracks the @Database class's
 * FQN; nesting it deeper would mean a path change on every package move.
 *
 * Version 1 carries one entity: [FavouriteRouteAtStopEntity]. Phase 8 will introduce a
 * known-disruption table at v2, at which point a real `Migration` lands next to this class
 * and the `OpenPtvDatabaseMigrationTest` infrastructure already in `androidTest/` exercises it.
 *
 * `exportSchema = true` so Room writes `1.json` under the configured schema directory at build
 * time. The JSON is committed (acceptance criterion: schema diff visible in PR) and serves as
 * the source of truth for migration tests.
 */
@Database(
    entities = [FavouriteRouteAtStopEntity::class],
    version = OpenPtvDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(RouteTypeConverter::class)
abstract class OpenPtvDatabase : RoomDatabase() {
    abstract fun favouriteRouteAtStopDao(): FavouriteRouteAtStopDao

    companion object {
        const val VERSION: Int = 1

        /**
         * Filename used by `Room.databaseBuilder`. Centralised so the migration test harness
         * can open the same on-disk file the production app uses (rather than re-deriving it
         * from a string literal that could drift).
         */
        const val DATABASE_NAME: String = "openptv.db"
    }
}
