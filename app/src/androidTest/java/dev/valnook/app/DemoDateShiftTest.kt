package dev.valnook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.app.di.currentBuildInfo
import dev.valnook.data.database.DemoDateShift
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.feature.cash.observeCreditCycleSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The bundled demo, moved onto another "today", keeps every record at the same distance from it. */
@RunWith(AndroidJUnit4::class)
class DemoDateShiftTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = 86_400_000L

    private fun <T> demo(days: Long, block: (ValnookDatabase) -> T): T {
        val name = "demo-shift-${UUID.randomUUID()}.db"
        val database = ValnookDatabase.fromAsset(context, name, "database/valnook-demo-v30.db")
        try {
            DemoDateShift.shift(database.openHelper.writableDatabase, AppSessionManager.DEMO_TODAY,
                AppSessionManager.DEMO_TODAY.plusDays(days))
            return block(database)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    private fun ValnookDatabase.longs(sql: String) = openHelper.readableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun ValnookDatabase.texts(sql: String) = openHelper.readableDatabase.query(sql).use { cursor ->
        buildList<String?> { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun snapshot(database: ValnookDatabase) = mapOf(
        "bills" to database.longs("SELECT occurred_at_ms FROM bill_entries ORDER BY id"),
        "entries" to database.longs("SELECT occurred_at_ms FROM cash_entries ORDER BY id"),
        "trades" to database.longs("SELECT occurred_at_ms FROM investment_trades ORDER BY id"),
        "prices" to database.longs("SELECT effective_at_ms FROM instrument_price_history ORDER BY id"),
        "deposits" to database.longs("SELECT start_epoch_day FROM term_deposits ORDER BY id"),
        "rates" to database.longs("SELECT local_day FROM fx_rate_history ORDER BY base_currency, quote_currency, local_day"),
        "audit" to database.longs("SELECT business_at_ms FROM audit_events ORDER BY event_id"),
        "openings" to database.longs("SELECT opening_at_ms FROM investments ORDER BY id"),
        "statements" to database.longs("SELECT statement_day FROM credit_account_profiles ORDER BY account_id"),
        "dues" to database.longs("SELECT due_rule_value FROM credit_account_profiles ORDER BY account_id"))

    @Test fun every_date_moves_by_the_same_number_of_days() {
        val days = 40L
        val before = demo(0) { database ->
            snapshot(database) to Triple(
                database.texts("SELECT business_local_date FROM audit_events ORDER BY event_id"),
                database.texts("SELECT after_json FROM audit_events WHERE entity_kind='BILL' ORDER BY event_id LIMIT 1").single(),
                database.texts("SELECT due_rule_type FROM credit_account_profiles ORDER BY account_id"))
        }
        val after = demo(days) { database ->
            snapshot(database) to Triple(
                database.texts("SELECT business_local_date FROM audit_events ORDER BY event_id"),
                database.texts("SELECT after_json FROM audit_events WHERE entity_kind='BILL' ORDER BY event_id LIMIT 1").single(),
                database.texts("SELECT due_rule_type FROM credit_account_profiles ORDER BY account_id"))
        }
        val (old, oldText) = before
        val (new, newText) = after
        listOf("bills", "entries", "trades", "prices", "audit").forEach { key ->
            assertEquals(key, old.getValue(key).map { if (it > 0) it + days * day else it }, new.getValue(key))
        }
        listOf("deposits", "rates").forEach { key -> assertEquals(key, old.getValue(key).map { it + days }, new.getValue(key)) }
        // An opening that never had a time stays without one.
        assertEquals(old.getValue("openings"), new.getValue("openings"))
        assertEquals(oldText.first.map { text -> text?.let { LocalDate.parse(it).plusDays(days).toString() } }, newText.first)
        assertEquals(JSONObject(oldText.second).getString("occurred_at_ms").toLong() + days * day,
            JSONObject(newText.second).getString("occurred_at_ms").toLong())
        val from = AppSessionManager.DEMO_TODAY
        assertEquals(old.getValue("statements").map { DemoDateShift.monthDay(it.toInt(), days, from).toLong() },
            new.getValue("statements"))
        assertEquals(old.getValue("dues").zip(oldText.third) { due, rule ->
            if (rule == "FIXED_DAY_OF_MONTH") DemoDateShift.monthDay(due.toInt(), days, from).toLong() else due
        }, new.getValue("dues"))
    }

    /** What each card's previous statement still owes, seen on the moved "today". */
    private fun owed(days: Long): Map<String, Long?> = demo(days) { database ->
        val today = AppSessionManager.DEMO_TODAY.plusDays(days)
        val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
        val graph = createDatabaseGraph(context, database, clock, currentBuildInfo(), persistentPrivateCards = false)
        runBlocking {
            database.longs("SELECT account_id FROM credit_account_profiles ORDER BY account_id").associate { id ->
                val account = requireNotNull(graph.cash.observeCashAccount(id).first())
                account.name to graph.cashPages.observeCreditCycleSummary(id, requireNotNull(account.creditProfile), today, zone)
                    .first().previousRemainingMinor
            }
        }
    }

    @Test fun the_bundled_demo_opens_at_the_current_schema_without_a_cloud_connection() = demo(0) { database ->
        assertEquals(dev.valnook.data.database.DATABASE_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
        val cloud = runBlocking { database.cloudBackup().state() }
        org.junit.Assert.assertNull(cloud.account_reference)
        org.junit.Assert.assertFalse(cloud.automatic_enabled)
    }

    @Test fun cards_owe_what_they_owed_wherever_today_is() {
        val base = owed(0)
        // Within a month, across a year end, and back to the tests' fixed date.
        listOf(11L, 365L, -49L).forEach { days ->
            val moved = owed(days)
            Log.i("DemoDateShiftTest", "shift $days: $moved (base $base)")
            assertEquals("shift $days", base, moved)
        }
    }
}
