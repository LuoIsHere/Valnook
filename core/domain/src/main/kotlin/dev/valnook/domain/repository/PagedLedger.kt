package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

data class LedgerCursor(val time: Long, val id: Long)

interface PagedCashRepository {
    fun observeRevision(accountId: Long, currencyCode: String): Flow<Long>
    suspend fun page(accountId: Long, currencyCode: String, cursor: LedgerCursor?, size: Int): List<CashEntry>
    fun observeCashAccountRevision(cashAccountId: Long): Flow<Long>
    suspend fun cashAccountPage(cashAccountId: Long, cursor: LedgerCursor?, size: Int): List<CashEntry>
}

interface PagedDepositRepository {
    fun observeRevision(accountId: Long): Flow<Long>
    suspend fun page(accountId: Long, closed: Boolean, cursor: LedgerCursor?, size: Int): List<TermDeposit>
}
