package dev.valnook.data.repository

import dev.valnook.data.database.BillAccountRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillAccountChoicesTest {
    private fun row(id: Long, balance: Long, credit: Boolean = true, limit: Long? = null, source: Long? = null) =
        BillAccountRow(id, 1, "Synthetic Bank", "Card $id", "CNY", balance, false, 0, true, 0, id, "SYMBOL",
            "account_balance", credit, limit, source)

    private fun available(rows: List<BillAccountRow>, id: Long) =
        choices(rows).single { it.cashAccountId == id }.availableCreditMinor

    @Test fun an_overpayment_adds_to_the_limit() {
        assertEquals(4_500L, available(listOf(row(1, 4_500, limit = 0)), 1))
        assertEquals(10_200L, available(listOf(row(1, 200, limit = 10_000)), 1))
    }

    @Test fun a_shared_limit_counts_every_member() {
        val rows = listOf(row(1, -12_000, limit = 20_000), row(2, -3_000, source = 1), row(3, 3_000, source = 1))
        assertEquals(8_000L, available(rows, 1))
        assertEquals(8_000L, available(rows, 3))
    }

    @Test fun without_a_limit_only_an_overpayment_is_available() {
        assertEquals(700L, available(listOf(row(1, 700)), 1))
        assertNull(available(listOf(row(1, -700)), 1))
        assertNull(available(listOf(row(1, 700, credit = false)), 1))
    }
}
