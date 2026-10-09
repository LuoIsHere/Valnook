package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

data class LedgerCursor(val time: Long, val id: Long)

interface PagedCashRepository {
    fun observeMonthBounds(cashAccountId: Long): Flow<LedgerBounds> = kotlinx.coroutines.flow.flowOf(LedgerBounds(null,null))

    suspend fun cashAccountMonthPage(cashAccountId: Long, month: LedgerMonth, cursor: LedgerCursor?, size: Int): List<CashEntry> =
        readMonthPage(month, cursor, size, { it: CashEntry -> it.occurred_at_ms },
            { LedgerCursor(it.occurred_at_ms, it.id) }) { next, limit -> cashAccountPage(cashAccountId, next, limit) }
    fun observeRevision(accountId: Long, currencyCode: String): Flow<Long>
    suspend fun page(accountId: Long, currencyCode: String, cursor: LedgerCursor?, size: Int): List<CashEntry>
    fun observeCashAccountRevision(cashAccountId: Long): Flow<Long>
    suspend fun cashAccountPage(cashAccountId: Long, cursor: LedgerCursor?, size: Int): List<CashEntry>
}

interface PagedDepositRepository {
    suspend fun monthPage(accountId: Long, month: LedgerMonth, cursor: LedgerCursor?, size: Int): List<TermDeposit> =
        throw UnsupportedOperationException("Settlement month queries require a business-date index")

    fun observeRevision(accountId: Long): Flow<Long>
    suspend fun page(accountId: Long, closed: Boolean, cursor: LedgerCursor?, size: Int): List<TermDeposit>
}
