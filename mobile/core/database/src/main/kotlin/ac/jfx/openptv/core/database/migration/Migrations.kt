package ac.jfx.openptv.core.database.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema v1 → v2: replace `favourite_routes_at_stop` (keyed `(stopId, routeId, directionId)`)
 * with `favourite_destinations_at_stop` (keyed `(stopId, destinationKey)`).
 *
 * The user-visible semantic change: favourites now track destination blocks as shown on
 * stop-detail, not specific routes. At interchanges where multiple routes feed the same
 * destination (Caulfield → "City" via Cranbourne + Pakenham + Frankston), one favourite covers
 * the whole block. If the user previously starred two routes that fed the same destination,
 * those rows collapse into one — earliest `position` and `addedAt` win. This is intentional
 * (issue #137).
 *
 * `MIN(routeType)` during the collapse is safe because every collapsed row sits at the same stop
 * and a stop is single-mode. `LOWER(directionName)` matches the runtime `toDestinationKey()`
 * rule used by stop-detail and the use cases — change one, change the other.
 *
 * The position re-densification uses a `ROW_NUMBER()` window function. SQLite 3.25+ ships on
 * every supported Android (API 30+ is the project's `minSdk`).
 */
val MIGRATION_1_2: Migration =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favourite_destinations_at_stop` (
                    `stopId` INTEGER NOT NULL,
                    `destinationKey` TEXT NOT NULL,
                    `routeType` TEXT NOT NULL,
                    `stopName` TEXT NOT NULL,
                    `stopSuburb` TEXT NOT NULL,
                    `destinationName` TEXT NOT NULL,
                    `lat` REAL NOT NULL,
                    `lng` REAL NOT NULL,
                    `position` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`stopId`, `destinationKey`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `favourite_destinations_at_stop` (
                    stopId, destinationKey, routeType,
                    stopName, stopSuburb, destinationName,
                    lat, lng, position, addedAt
                )
                SELECT
                    stopId,
                    LOWER(directionName)        AS destinationKey,
                    MIN(routeType)              AS routeType,
                    MIN(stopName)               AS stopName,
                    MIN(stopSuburb)             AS stopSuburb,
                    MIN(directionName)          AS destinationName,
                    MIN(lat)                    AS lat,
                    MIN(lng)                    AS lng,
                    MIN(position)               AS position,
                    MIN(addedAt)                AS addedAt
                FROM `favourite_routes_at_stop`
                GROUP BY stopId, LOWER(directionName)
                """.trimIndent(),
            )
            // Densify positions so collapsed rows are contiguous 0..N-1.
            db.execSQL(
                """
                WITH ranked AS (
                    SELECT
                        stopId,
                        destinationKey,
                        ROW_NUMBER() OVER (
                            ORDER BY position, stopId, destinationKey
                        ) - 1 AS newPosition
                    FROM `favourite_destinations_at_stop`
                )
                UPDATE `favourite_destinations_at_stop`
                SET position = (
                    SELECT newPosition
                    FROM ranked
                    WHERE ranked.stopId = favourite_destinations_at_stop.stopId
                      AND ranked.destinationKey = favourite_destinations_at_stop.destinationKey
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `favourite_routes_at_stop`")
        }
    }

/**
 * Schema v2 → v3: add the `favourite_journeys` table (issue #209). Pure additive — no existing
 * rows are touched, so the migration is a single `CREATE TABLE` whose shape must match Room's
 * generated schema for [ac.jfx.openptv.core.database.entity.FavouriteJourneyEntity] exactly
 * (the migration test validates against the exported `3.json`).
 */
val MIGRATION_2_3: Migration =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `favourite_journeys` (
                    `originStopId` INTEGER NOT NULL,
                    `originStopName` TEXT NOT NULL,
                    `originStopSuburb` TEXT NOT NULL,
                    `originRouteType` TEXT NOT NULL,
                    `originLat` REAL NOT NULL,
                    `originLng` REAL NOT NULL,
                    `destinationStopId` INTEGER NOT NULL,
                    `destinationStopName` TEXT NOT NULL,
                    `destinationStopSuburb` TEXT NOT NULL,
                    `destinationRouteType` TEXT NOT NULL,
                    `destinationLat` REAL NOT NULL,
                    `destinationLng` REAL NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`originStopId`, `destinationStopId`)
                )
                """.trimIndent(),
            )
        }
    }
