package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomSettings
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.repository.SaveLanguage
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Demo names follow the chosen language; "System" means the device's language, whatever the app shows. */
@RunWith(AndroidJUnit4::class)
class DemoLanguageDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-06T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private lateinit var db: ValnookDatabase
    private val defaultLocale = Locale.getDefault()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        db.openHelper.writableDatabase.apply {
            execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms) VALUES (7,'招商银行','',0,0)")
            execSQL("INSERT INTO demo_labels VALUES ('ACCOUNT',7,'NAME','招商银行','China Merchants Bank')")
        }
    }

    @After fun close() { Locale.setDefault(defaultLocale); db.close() }

    private suspend fun revision() = db.overview().settings()?.revision ?: 0
    private suspend fun name() = db.overview().allAccounts().single { it.id == 7L }.name

    private fun switchTo(device: String, vararg steps: AppLanguage): String = runBlocking {
        val settings = RoomSettings(db, clock) { device }
        steps.forEach { language ->
            // The app's own locale is whatever it showed last, as on a phone right after switching.
            if (language == AppLanguage.ENGLISH) Locale.setDefault(Locale.ENGLISH)
            settings.applyChange(SaveLanguage(revision(), language))
        }
        name()
    }

    @Test fun leaving_english_for_system_on_a_chinese_device_restores_chinese_names() {
        assertEquals("招商银行", switchTo("zh", AppLanguage.ENGLISH, AppLanguage.SYSTEM))
    }

    @Test fun system_on_an_english_device_uses_english_names() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("China Merchants Bank", switchTo("en", AppLanguage.SYSTEM))
    }

    @Test fun a_chosen_language_ignores_the_device() {
        assertEquals("China Merchants Bank", switchTo("zh", AppLanguage.ENGLISH))
        assertEquals("招商银行", switchTo("en", AppLanguage.ZH_HANS))
    }
}
