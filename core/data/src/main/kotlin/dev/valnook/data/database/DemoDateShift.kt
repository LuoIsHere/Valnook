package dev.valnook.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Moves a freshly copied demo dataset by whole days, so that the day it was made as "today" becomes [shift]'s `to`
 * and every record keeps its distance from it: this month's bills stay this month's, wherever the calendar is.
 *
 * Covered: every `*_ms` column (millisecond instants, 0 and null left as they are), every `*epoch_day` column and the
 * exchange-rate days (epoch days), the audit trail's local date and the instants and days inside its JSON, and each
 * card's statement day and fixed due day. A statement day moves to the day of the month its statement date lands
 * on, so cycles follow their spending; months of different lengths can still move a cycle's edge by a day or two.
 *
 * Instants move by whole 24-hour days, which keeps the local time except across a daylight-saving change.
 */
object DemoDateShift {
    private const val DAY_MS = 86_400_000L
    private val DAY_COLUMNS = setOf("local_day", "source_date", "last_success_day", "data_date")
    private val SKIPPED_TABLES = setOf("android_metadata", "room_master_table", "sqlite_sequence")
    private val AUDIT_JSON = listOf("before_json", "after_json", "cash_effects_json")

    /** Shifts [db] from [from] to [to] in one transaction; nothing happens when they are the same day. */
    fun shift(db: SupportSQLiteDatabase, from: LocalDate, to: LocalDate) {
        val days = ChronoUnit.DAYS.between(from, to)
        if (days == 0L) return
        db.beginTransaction()
        try {
            tables(db).forEach { table ->
                columns(db, table).forEach { column ->
                    when {
                        column.endsWith("_ms") -> move(db, table, column, days * DAY_MS)
                        column.endsWith("epoch_day") || column in DAY_COLUMNS -> move(db, table, column, days)
                    }
                }
            }
            db.execSQL("UPDATE audit_events SET business_local_date=date(business_local_date, ?) " +
                "WHERE business_local_date IS NOT NULL", arrayOf<Any>("${if (days > 0) "+" else ""}$days days"))
            shiftAuditJson(db, days, from)
            shiftCreditDays(db, days, from)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The day of the month a card's [day] lands on once its date in [from]'s month moves by [days]. */
    fun monthDay(day: Int, days: Long, from: LocalDate): Int {
        val month = YearMonth.from(from)
        return month.atDay(day.coerceAtMost(month.lengthOfMonth())).plusDays(days).dayOfMonth
    }

    private fun tables(db: SupportSQLiteDatabase) =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.filter { it !in SKIPPED_TABLES }

    private fun columns(db: SupportSQLiteDatabase, table: String) =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            buildList { while (cursor.moveToNext()) if (cursor.getString(type).equals("INTEGER", true)) add(cursor.getString(name)) }
        }

    /**
     * Positive values only. They pass through their negatives first, so a key or unique index on the column never
     * sees two rows on the same value halfway through.
     */
    private fun move(db: SupportSQLiteDatabase, table: String, column: String, delta: Long) {
        db.execSQL("UPDATE `$table` SET `$column`=-(`$column`+?) WHERE `$column`>0", arrayOf<Any>(delta))
        db.execSQL("UPDATE `$table` SET `$column`=-`$column` WHERE `$column`<0")
    }

    private fun shiftAuditJson(db: SupportSQLiteDatabase, days: Long, from: LocalDate) {
        val rows = db.query("SELECT event_id, ${AUDIT_JSON.joinToString()} FROM audit_events").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to AUDIT_JSON.indices.map { cursor.getString(it + 1) })
            }
        }
        rows.forEach { (id, values) ->
            val moved = values.map { it?.let { text -> shiftJson(text, days, from) } }
            if (moved != values) db.execSQL(
                "UPDATE audit_events SET ${AUDIT_JSON.joinToString { "$it=?" }} WHERE event_id=?",
                (moved + id).toTypedArray())
        }
    }

    /** The same rules inside an audit record: instants, epoch days and statement days, kept as the strings they are. */
    internal fun shiftJson(text: String, days: Long, from: LocalDate): String {
        fun walk(value: Any?): Any? = when (value) {
            is JSONObject -> value.apply {
                keys().asSequence().toList().forEach { key ->
                    val number = (opt(key) as? String)?.toLongOrNull()
                    when {
                        number == null -> walk(opt(key))
                        number <= 0 -> Unit
                        key.endsWith("_ms") -> put(key, (number + days * DAY_MS).toString())
                        key.endsWith("epoch_day") -> put(key, (number + days).toString())
                        key == "statement_day" -> put(key, monthDay(number.toInt(), days, from).toString())
                    }
                }
            }
            is JSONArray -> value.apply { for (i in 0 until length()) walk(opt(i)) }
            else -> value
        }
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("{") -> walk(JSONObject(text)).toString()
            trimmed.startsWith("[") -> walk(JSONArray(text)).toString()
            else -> text
        }
    }

    private fun shiftCreditDays(db: SupportSQLiteDatabase, days: Long, from: LocalDate) {
        val profiles = db.query("SELECT account_id, statement_day, due_rule_type, due_rule_value FROM credit_account_profiles")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(listOf(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getLong(3)))
                }
            }
        profiles.forEach { (id, statement, rule, due) ->
            val fixed = rule == "FIXED_DAY_OF_MONTH"
            db.execSQL("UPDATE credit_account_profiles SET statement_day=?, due_rule_value=? WHERE account_id=?", arrayOf(
                monthDay((statement as Long).toInt(), days, from),
                if (fixed) monthDay((due as Long).toInt(), days, from) else due, id))
        }
    }
}
