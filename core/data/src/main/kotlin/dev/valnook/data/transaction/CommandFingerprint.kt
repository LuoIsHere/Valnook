package dev.valnook.data.transaction

import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import java.security.MessageDigest

internal object CommandFingerprint {
    fun fingerprint(command: FinancialCommand): Pair<String,String> {
        val fields: List<Any?> = when(command) {
            is SaveAssetType -> listOf("TYPE_SAVE", command.typeId, command.name.trim())
            is SaveAccount -> listOf("ACCOUNT_SAVE", command.accountId, command.expectedRevision,
                command.name.trim(), command.note) + command.cashChanges.sortedWith(compareBy({ it.cashAccountId ?: Long.MAX_VALUE }, { it.name }))
                    .flatMap { listOf(it.cashAccountId, it.name.trim(), it.note, Currency.of(it.currencyCode).code,
                        it.balanceMinor, it.expectedRevision, it.type.name, it.credit?.creditLimitMinor,
                        it.credit?.statementDay, it.credit?.dueRule?.javaClass?.simpleName,
                        it.credit?.dueRule?.value, it.credit?.limitSourceAccountId) } +
                // Preserve old fingerprints for commands that do not request presentation changes.
                (if (command.cashChanges.any { it.displayOrder != null })
                    listOf("DISPLAY_ORDER") + command.cashChanges.flatMap { listOf(it.cashAccountId, it.name.trim(), it.displayOrder) }
                else emptyList()) + (command.iconChange?.let {
                    listOf("ACCOUNT_ICON", it.icon.type.name, it.icon.value, it.image?.let(AccountIconImages::digest))
                } ?: emptyList()) + (if (command.showDepositSummary != null || command.showInvestmentSummary != null ||
                    command.cashChanges.any { it.includeInAvailableCash != null || it.showOnAccountsPage != null })
                    listOf("ACCOUNT_PRESENTATION", command.showDepositSummary, command.showInvestmentSummary) +
                        command.cashChanges.sortedBy { it.cashAccountId ?: Long.MAX_VALUE }.flatMap {
                            listOf(it.cashAccountId, it.name.trim(), it.includeInAvailableCash, it.showOnAccountsPage)
                        } else emptyList())
            is DeleteBalanceAccount -> listOf("BALANCE_ACCOUNT_DELETE", command.accountId,
                command.balanceAccountId, command.expectedRevision, command.confirmation?.ticket, command.confirmation?.code)
            is DeleteAccount -> listOf("ACCOUNT_DELETE", command.accountId, command.expectedRevision,
                command.confirmation.ticket, command.confirmation.code)
            is SaveInstrument -> listOf("INSTRUMENT_SAVE", command.instrumentId, command.expectedRevision,
                command.name.trim(), command.symbol.trim(), command.typeId, Currency.of(command.currencyCode).code,
                command.currentPriceE5, command.currencyPriceConfirmed)
            is UpdateInstrumentPrices -> listOf("INSTRUMENT_PRICES_UPDATE") + command.changes
                .sortedBy { it.instrumentId }.flatMap { listOf(it.instrumentId, it.expectedRevision, it.priceE5) }
            is EditInstrumentPrice -> listOf("INSTRUMENT_PRICE_EDIT", command.priceRecordId,
                command.expectedRevision, command.priceE5, command.effectiveAtMs)
            is CreateInvestmentPosition -> listOf("POSITION_CREATE", command.accountId, command.instrumentId)
            is SetCashBalance -> listOf("CASH_SET",command.account_id,Currency.of(command.currency_code).code,
                command.balance_minor,command.expected_revision,command.cashAccountId,command.name.trim(),command.note)
            is OpenTermDeposit -> listOf("TERM_OPEN",command.account_id,Currency.of(command.currency_code).code,command.principal_minor,
                command.annual_rate_percent_e8,command.start_epoch_day,command.end_epoch_day,command.cash_linked,
                command.cashAccountId)
            is CloseTermDeposit -> listOf("TERM_CLOSE",command.deposit_id,command.cash_linked,command.cashAccountId)
            is EditTermDeposit -> listOf("TERM_EDIT",command.deposit_id,command.expected_revision,command.principal_minor,
                command.annual_rate_percent_e8,command.start_epoch_day,command.end_epoch_day,command.open_cash_linked,
                command.close_cash_linked,command.openCashAccountId,command.closeCashAccountId)
            is RecordInvestmentTrade -> listOf(command.direction.name,command.investment_id,command.quantity_e8,
                command.execution_price_e8,command.occurred_at_ms,command.cash_linked,command.cashAccountId,
                command.fee_minor) + (if (command.investment_id == 0L) listOf(command.accountId, command.instrumentId) else emptyList())
            is EditInvestmentTrade -> listOf("TRADE_EDIT",command.trade_id,command.expected_revision,command.direction,
                command.quantity_e8,command.execution_price_e8,command.occurred_at_ms,command.cash_linked,
                command.cashAccountId,command.fee_minor)
            is DeleteInvestmentTrade -> listOf("TRADE_DELETE",command.trade_id,command.expected_revision)
            is EditCashEntry -> listOf("CASH_EDIT",command.entry_id,command.expected_revision,command.delta_minor,
                command.occurred_at_ms,command.note.trim())
        }
        val canonical = fields.joinToString("") { field -> val s=field?.toString() ?: "<null>"
            "${s.length}:$s" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return fields.first().toString() to digest
    }
}
