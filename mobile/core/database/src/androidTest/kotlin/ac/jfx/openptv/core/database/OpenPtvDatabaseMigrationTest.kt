package ac.jfx.openptv.core.database

import ac.jfx.openptv.OpenPtvDatabase
import ac.jfx.openptv.core.database.migration.MIGRATION_1_2
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests for [OpenPtvDatabase].
 *
 * v1 → v2 collapses per-route favourites into per-destination favourites. Several rows that share
 * `(stopId, LOWER(directionName))` fold into one row, earliest `position` and `addedAt` survive.
 * These tests cover that behaviour because the SQL is the only thing standing between a real user
 * with pre-existing favourites and a silent data-loss bug at upgrade time.
 */
@RunWith(AndroidJUnit4::class)
class OpenPtvDatabaseMigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenPtvDatabase::class.java,
        )

    @Test
    fun createsV1Database() {
        helper.createDatabase(TEST_DB_NAME, 1).use { db ->
            assertThat(db.version).isEqualTo(1)
        }
    }

    @Test
    fun createsV2Database() {
        helper.createDatabase(TEST_DB_NAME, 2).use { db ->
            assertThat(db.version).isEqualTo(2)
        }
    }

    @Test
    fun migrate_v1_to_v2_collapsesByDestination() {
        helper.createDatabase(TEST_DB_NAME, 1).use { db ->
            // Caulfield → City via Cranbourne
            db.insertV1Row(
                stopId = CAULFIELD_STOP_ID,
                routeId = 1,
                directionId = 1,
                directionName = "City",
                stopName = "Caulfield Railway Station",
                stopSuburb = "Caulfield East",
                position = 0,
                addedAt = 1_000L,
            )
            // Caulfield → City via Pakenham (collapses with the above)
            db.insertV1Row(
                stopId = CAULFIELD_STOP_ID,
                routeId = 2,
                directionId = 2,
                directionName = "City",
                stopName = "Caulfield Railway Station",
                stopSuburb = "Caulfield East",
                position = 1,
                addedAt = 2_000L,
            )
            // Caulfield → Frankston (separate destination at same stop, kept)
            db.insertV1Row(
                stopId = CAULFIELD_STOP_ID,
                routeId = 3,
                directionId = 3,
                directionName = "Frankston",
                stopName = "Caulfield Railway Station",
                stopSuburb = "Caulfield East",
                position = 2,
                addedAt = 3_000L,
            )
            // Different stop entirely, kept
            db.insertV1Row(
                stopId = FLINDERS_STOP_ID,
                routeId = 1881,
                directionId = 9,
                directionName = "North Coburg",
                stopName = "Flinders Street Railway Station",
                stopSuburb = "Melbourne City",
                position = 3,
                addedAt = 4_000L,
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)

        migrated.query(
            "SELECT stopId, destinationKey, destinationName, position " +
                "FROM favourite_destinations_at_stop ORDER BY position",
        ).use { cursor ->
            val rows = mutableListOf<MigratedRow>()
            while (cursor.moveToNext()) {
                rows +=
                    MigratedRow(
                        stopId = cursor.getInt(0),
                        destinationKey = cursor.getString(1),
                        destinationName = cursor.getString(2),
                        position = cursor.getInt(3),
                    )
            }
            assertThat(rows).containsExactly(
                MigratedRow(CAULFIELD_STOP_ID, "city", "City", 0),
                MigratedRow(CAULFIELD_STOP_ID, "frankston", "Frankston", 1),
                MigratedRow(FLINDERS_STOP_ID, "north coburg", "North Coburg", 2),
            ).inOrder()
        }
    }

    @Test
    fun migrate_v1_to_v2_preservesEarliestAddedAtAndPositionOnCollapse() {
        helper.createDatabase(TEST_DB_NAME, 1).use { db ->
            db.insertV1Row(
                stopId = CAULFIELD_STOP_ID,
                routeId = 1,
                directionId = 1,
                directionName = "City",
                position = 5,
                addedAt = 2_000L,
            )
            db.insertV1Row(
                stopId = CAULFIELD_STOP_ID,
                routeId = 2,
                directionId = 2,
                directionName = "City",
                position = 3,
                addedAt = 1_000L,
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)

        migrated.query(
            "SELECT addedAt, position FROM favourite_destinations_at_stop " +
                "WHERE stopId = ? AND destinationKey = ?",
            arrayOf<Any>(CAULFIELD_STOP_ID, "city"),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1_000L)
            // Position densified to 0 (single row after collapse).
            assertThat(cursor.getInt(1)).isEqualTo(0)
        }
    }

    @Test
    fun migrate_v1_to_v2_dropsOldTable() {
        helper.createDatabase(TEST_DB_NAME, 1).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MIGRATION_1_2)

        migrated.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'favourite_routes_at_stop'",
        ).use { cursor ->
            assertThat(cursor.count).isEqualTo(0)
        }
    }

    @Suppress("LongParameterList")
    private fun SupportSQLiteDatabase.insertV1Row(
        stopId: Int,
        routeId: Int,
        directionId: Int,
        directionName: String,
        stopName: String = "Stop $stopId",
        stopSuburb: String = "Suburb",
        routeType: String = "Train",
        routeNumber: String = "$routeId",
        routeName: String = "Route $routeId",
        lat: Double = -37.0,
        lng: Double = 144.0,
        position: Int,
        addedAt: Long,
    ) {
        execSQL(
            "INSERT INTO favourite_routes_at_stop " +
                "(stopId, routeType, routeId, directionId, stopName, stopSuburb, routeNumber, " +
                "routeName, directionName, lat, lng, position, addedAt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                stopId,
                routeType,
                routeId,
                directionId,
                stopName,
                stopSuburb,
                routeNumber,
                routeName,
                directionName,
                lat,
                lng,
                position,
                addedAt,
            ),
        )
    }

    private data class MigratedRow(
        val stopId: Int,
        val destinationKey: String,
        val destinationName: String,
        val position: Int,
    )

    private companion object {
        const val TEST_DB_NAME = "openptv-migration-test.db"
        const val CAULFIELD_STOP_ID = 22180
        const val FLINDERS_STOP_ID = 1071
    }
}
