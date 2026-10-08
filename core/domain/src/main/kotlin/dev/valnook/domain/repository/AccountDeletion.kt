package dev.valnook.domain.repository

data class DeletionConfirmation(val ticket: String, val code: String)
data class CreditLimitTransfer(val name: String, val currencyCode: String, val limitMinor: Long)
data class AccountDeletionPreview(
    val ticket: String, val code: String, val accountId: Long, val balanceAccountId: Long?,
    val name: String, val revision: Long, val currencyCode: String?, val balanceMinor: Long?,
    val cashCount: Int, val entryCount: Int, val depositCount: Int, val tradeCount: Int,
    val transfers: List<CreditLimitTransfer>
)
