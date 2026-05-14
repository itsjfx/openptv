package ac.jfx.openptv.core.database

import ac.jfx.openptv.OpenPtvDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test scaffolding for [OpenPtvDatabase].
 *
 * v1 has no predecessors so there are no migrations to exercise yet. The point of this file
 * landing in PR #31 is that the **harness** is in place: a Gradle module, a working
 * [MigrationTestHelper] configured against the committed schema directory, and an
 * `androidTest` runner. Phase 8 introduces a v2 schema (the known-disruption table) and that
 * PR can add `migrate_v1_to_v2` here next to a real `Migration` object, instead of also
 * having to invent the harness.
 *
 * Acceptance criterion from `docs/mobile/phase-04-favourites.md`: "Migration test scaffolding
 * runs in CI (placeholder migration "from-v0-to-v1" is fine)." This file is that.
 */
@RunWith(AndroidJUnit4::class)
class OpenPtvDatabaseMigrationTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpenPtvDatabase::class.java,
        )

    /**
     * Confirms the helper can open v1 against the committed schema JSON. If the schema export
     * ever drifts (file missing, FQN changed) this test fails before any real migration is
     * ever needed.
     */
    @Test
    fun createsV1Database() {
        helper.createDatabase(TEST_DB_NAME, OpenPtvDatabase.VERSION).use { db ->
            assertThat(db.version).isEqualTo(OpenPtvDatabase.VERSION)
        }
    }

    private companion object {
        const val TEST_DB_NAME = "openptv-migration-test.db"
    }
}
