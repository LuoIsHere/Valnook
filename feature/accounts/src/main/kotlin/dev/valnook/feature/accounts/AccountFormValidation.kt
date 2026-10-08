package dev.valnook.feature.accounts

import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules

internal fun validateAccountForm(state: AccountEditUiState): Map<String, ErrorCode> = linkedMapOf<String, ErrorCode>().apply {
    fun name(key: String, text: String) { if (text.trim().isEmpty() || text.trim().length > 200) put(key, ErrorCode.NAME) }
    fun note(key: String, text: String) { if (text.length > 2000) put(key, ErrorCode.FORMAT) }
    fun parse(key: String, block: () -> Unit) {
        try { block() } catch (error: DomainException) { put(key, error.code) }
    }
    name("name", state.name)
    note("note", state.note)
    state.rows.forEach { row ->
        name("${row.key}:name", row.nameInput)
        note("${row.key}:note", row.noteInput)
        parse("${row.key}:balance") { DecimalRules.parse_signed_minor(row.balanceInput, row.currency) }
        if (row.type == BalanceAccountType.CREDIT) {
            if (row.limitSourceAccountId == null) parse("${row.key}:limit") {
                DecimalRules.parse_minor(row.creditLimitInput, row.currency)
            }
            if (row.statementDayInput.toIntOrNull() !in 1..31) put("${row.key}:statement", ErrorCode.INVALID_STATEMENT_DAY)
            val range = if (row.dueRuleType == "FIXED_DAY_OF_MONTH") 1..31 else 1..MAX_CREDIT_DUE_OFFSET_DAYS
            if (row.dueRuleValueInput.toIntOrNull() !in range) put("${row.key}:due", ErrorCode.INVALID_DUE_RULE)
        }
    }
}
