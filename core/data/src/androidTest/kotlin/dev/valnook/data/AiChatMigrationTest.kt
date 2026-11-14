package dev.valnook.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.ai.AiChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The conversation database moves on by automatic migrations: what was said before stays. */
@RunWith(AndroidJUnit4::class)
class AiChatMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AiChatDatabase::class.java,
        emptyList(), androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory())

    @Test fun v1_to_v2_keeps_conversations_and_marks_nothing_as_shared() {
        val name = "ai-chat-migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO conversations(id,title,created_at_ms,last_at_ms,tokens) VALUES (1,'Lunch',1,2,120)")
            db.execSQL("INSERT INTO messages(id,conversation_id,kind,text,raw,reasoning,thinking_ms,input_tokens,output_tokens," +
                "total_tokens,cached_tokens,calls,problem,no_drafts,confirmed_bills,created_at_ms) " +
                "VALUES (1,1,'USER','Lunch 25',NULL,NULL,NULL,NULL,NULL,NULL,NULL,0,NULL,0,'[]',1)")
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT text, shared FROM messages WHERE id=1").use {
                it.moveToFirst()
                assertEquals("Lunch 25", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            db.query("SELECT tokens FROM conversations WHERE id=1").use { it.moveToFirst(); assertEquals(120, it.getInt(0)) }
        }
    }
}
