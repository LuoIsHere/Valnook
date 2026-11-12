package dev.valnook.domain.repository

data class DeletionConfirmation(val ticket: String, val code: String)
data class CreditLimitTransfer(val name: String, val currencyCode: String, val limitMinor: Long)
data class AccountDeletionPreview(
    val ticket: String, val code: String, val accountId: Long, val balanceAccountId: Long?,
    val name: String, val revision: Long, val currencyCode: String?, val balanceMinor: Long?,
    val cashCount: Int, val entryCount: Int, val depositCount: Int, val tradeCount: Int,
    val transfers: List<CreditLimitTransfer>,
    /** Bills that stay after the deletion, showing the account as deleted. */
    val billCount: Int = 0,
)

/**
 * What deleting an instrument removes, in every account. [code] is empty when it has no trades, which needs no
 * code; [marketValue] is in [currencyCode] at the current price.
 */
data class InstrumentDeletionPreview(
    val ticket: String, val code: String, val instrumentId: Long, val name: String, val revision: Long,
    val currencyCode: String, val accountCount: Int, val tradeCount: Int, val priceCount: Int,
    val cashEntryCount: Int, val holdingQuantityE8: Long, val marketValue: java.math.BigDecimal
) {
    val needsCode: Boolean get() = tradeCount > 0
}
