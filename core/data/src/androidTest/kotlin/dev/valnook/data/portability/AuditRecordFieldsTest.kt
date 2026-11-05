package dev.valnook.data.portability

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The records sheet reads audit JSON in Kotlin, because Android 13's SQLite has no JSON functions. */
@RunWith(AndroidJUnit4::class)
class AuditRecordFieldsTest {
    private val samples = listOf(
        "{}",
        """{"name":"Saved name"}""",
        """{"name":""}""",
        """{"name":null}""",
        """{"audit_instrument_name":"Fund","audit_instrument_symbol":"F1","audit_instrument_currency":"USD"}""",
        """{"audit_instrument_name":"No symbol","name":"Ignored"}""",
        """{"audit_instrument_name":null,"audit_instrument_symbol":"F1","name":"Plain"}""",
        """{"currency_code":null,"audit_cash_currency":"EUR","audit_deposit_currency":"JPY"}""",
        """{"audit_deposit_currency":"JPY"}""",
        """{"quantity_e8":9007199254740993,"execution_price_e8":-5,"fee_minor":0,"amount_minor":123456789012}""",
        """{"amount_minor":null,"delta_minor":-1250,"principal_minor":9}""",
        """{"principal_minor":100000}""",
        """{"audit_cash_account_name":"工资卡 · 日常收支","name":"引号\"和\\斜杠"}""",
    )

    private fun read(json: String) = AuditRecordFields(json, "42").let {
        listOf(it.subaccount, it.objectName, it.currency, it.quantity, it.unitPrice, it.fee, it.amount)
    }

    @Test fun fields_fall_back_in_the_same_order_as_before() {
        assertEquals(listOf("", "42", "", "", "", "", ""), read(""))
        assertEquals(listOf("", "42", "", "", "", "", ""), read("not json"))
        assertEquals("Saved name", read("""{"name":"Saved name"}""")[1])
        assertEquals("", read("""{"name":""}""")[1])
        assertEquals("42", read("""{"name":null}""")[1])
        assertEquals("Fund / F1", read("""{"audit_instrument_name":"Fund","audit_instrument_symbol":"F1"}""")[1])
        assertEquals("No symbol / ", read("""{"audit_instrument_name":"No symbol","name":"Ignored"}""")[1])
        assertEquals("EUR", read("""{"currency_code":null,"audit_cash_currency":"EUR","audit_deposit_currency":"JPY"}""")[2])
        assertEquals("-1250", read("""{"amount_minor":null,"delta_minor":-1250,"principal_minor":9}""")[6])
        assertEquals("9007199254740993", read("""{"quantity_e8":9007199254740993}""")[3])
    }

    /** Runs only where SQLite has the functions the sheet used to rely on. */
    @Test fun fields_match_what_json_extract_returned() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val db = SQLiteDatabase.create(null)
        try {
            samples.forEach { json ->
                db.rawQuery("""SELECT COALESCE(json_extract(?1,'$.audit_cash_account_name'),''),
                    CASE WHEN json_extract(?1,'$.audit_instrument_name') IS NOT NULL
                        THEN json_extract(?1,'$.audit_instrument_name')||' / '||COALESCE(json_extract(?1,'$.audit_instrument_symbol'),'')
                        ELSE COALESCE(json_extract(?1,'$.name'),'42','') END,
                    COALESCE(json_extract(?1,'$.currency_code'),json_extract(?1,'$.audit_instrument_currency'),
                        json_extract(?1,'$.audit_cash_currency'),json_extract(?1,'$.audit_deposit_currency'),''),
                    COALESCE(json_extract(?1,'$.quantity_e8'),''),COALESCE(json_extract(?1,'$.execution_price_e8'),''),
                    COALESCE(json_extract(?1,'$.fee_minor'),''),
                    COALESCE(json_extract(?1,'$.amount_minor'),json_extract(?1,'$.delta_minor'),json_extract(?1,'$.principal_minor'),'')""",
                    arrayOf(json)).use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(json, (0..6).map(cursor::getString), read(json))
                }
            }
        } finally { db.close() }
    }
}
