package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.FxLocalStateEntity
import dev.valnook.data.database.FxRateHistoryEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomDataMaintenance
import dev.valnook.data.repository.RoomSettings
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveLanguage
import dev.valnook.domain.model.AppLanguage
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FxHistoryDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private lateinit var db: ValnookDatabase
    private lateinit var settings: RoomSettings
    private val synced = FxLocalStateEntity(auto_enabled = true, notice_dismissed = true, last_success_day = 20_389,
        last_success_at_ms = 7, last_attempt_at_ms = 7, last_error = null, data_date = 20_388)

    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        settings = RoomSettings(db, clock)
        settings.applyChange(SaveFinancialSettings(0, Currency.of("CNY"), listOf(rate("USD", "CNY", "7.1"))))
        db.fx().insertHistory(listOf(
            FxRateHistoryEntity("CNY", "USD", 20_388, 20_388, "0.1408", 1),
            FxRateHistoryEntity("CNY", "USD", 20_389, 20_388, "0.1408", 2),
            FxRateHistoryEntity("CNY", "EUR", 20_389, 20_389, "0.1301", 2)))
        db.fx().saveLocalState(synced)
    }

    @After fun close() = db.close()

    private fun rate(source: String, target: String, value: String) = FxRate(Currency.of(source), Currency.of(target), BigDecimal(value))
    private suspend fun revision() = db.overview().settings()!!.revision

    @Test fun new_database_starts_with_the_switch_off_and_no_history() = runBlocking {
        val fresh = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        try {
            assertEquals(FxLocalStateEntity(), fresh.fx().localState())
            assertTrue(fresh.fx().history("CNY").isEmpty())
        } finally { fresh.close() }
    }

    @Test fun saving_rates_under_the_same_base_keeps_history_and_sync_record() = runBlocking {
        settings.applyChange(SaveFinancialSettings(revision(), Currency.of("CNY"), listOf(rate("USD", "CNY", "7.2"))))
        settings.applyChange(SaveLanguage(revision(), AppLanguage.ENGLISH))
        assertEquals(3, db.fx().history("CNY").size)
        assertEquals(synced, db.fx().localState())
    }

    @Test fun changing_the_base_drops_other_history_and_the_sync_record_but_keeps_the_switch() = runBlocking {
        settings.applyChange(SaveFinancialSettings(revision(), Currency.of("USD"), listOf(rate("CNY", "USD", "0.14"))))
        assertTrue(db.fx().history("CNY").isEmpty())
        assertEquals(FxLocalStateEntity(auto_enabled = true, notice_dismissed = true), db.fx().localState())
    }

    @Test fun clearing_data_removes_history_and_resets_the_switch_and_notice() = runBlocking {
        RoomDataMaintenance(db).clearBusinessData()
        assertTrue(db.fx().history("CNY").isEmpty())
        assertEquals(FxLocalStateEntity(), db.fx().localState())
        assertTrue(db.overview().rates().isEmpty())
    }

    @Test fun history_rows_are_never_overwritten() = runBlocking {
        try {
            db.fx().insertHistory(listOf(FxRateHistoryEntity("CNY", "USD", 20_389, 20_389, "0.2", 3)))
            throw AssertionError("Expected a conflict")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals("0.1408", db.fx().history("CNY").single { it.quote_currency == "USD" && it.local_day == 20_389L }.rate)
    }
}
