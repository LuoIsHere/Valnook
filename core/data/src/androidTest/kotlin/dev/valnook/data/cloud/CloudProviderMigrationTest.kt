package dev.valnook.data.cloud

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.MIGRATION_13_14
import dev.valnook.data.database.ValnookDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudProviderMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ValnookDatabase::class.java)

    @Test fun legacy_connection_is_preserved_but_every_old_schedule_is_invalidated() {
        val name = "onedrive-migration-synthetic"
        helper.createDatabase(name, 13).apply {
            execSQL("""INSERT INTO cloud_backup_state VALUES
                (1,'synthetic-account','Synthetic account','legacy-folder',3,1,24,'NONE',7,123,'cycle',
                'UPLOADING',NULL,100,90,0,'banner','AUTH_REQUIRED',NULL,1)""")
            close()
        }
        helper.runMigrationsAndValidate(name, 14, true, MIGRATION_13_14).apply {
            query("SELECT provider,account_reference,automatic_enabled,connection_generation,schedule_generation,next_due_at_utc_ms,attempt_state FROM cloud_backup_state").use {
                assertTrue(it.moveToFirst())
                assertEquals("GOOGLE_DRIVE", it.getString(0)); assertEquals("synthetic-account", it.getString(1))
                assertEquals(0, it.getInt(2)); assertEquals(4L, it.getLong(3)); assertEquals(8L, it.getLong(4))
                assertTrue(it.isNull(5)); assertEquals("CANCELLED", it.getString(6))
            }
            close()
        }
    }
}
