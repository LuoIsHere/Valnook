package dev.valnook.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.command.SubmissionSession
import dev.valnook.domain.model.AccountIcon
import dev.valnook.domain.model.AccountIconChange
import dev.valnook.domain.model.AccountIconType
import dev.valnook.domain.model.AccountSymbols
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.DeleteBalanceAccount
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.OverviewRepository
import dev.valnook.domain.repository.SaveAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class CashAccountRowDraft(
    val key: String,
    val cashAccountId: Long?,
    val nameInput: String,
    val noteInput: String,
    val currency: Currency,
    val balanceInput: String,
    val savedBalanceMinor: Long? = null,
    val expectedRevision: Long? = null,
    val type: BalanceAccountType = BalanceAccountType.SAVINGS,
    val creditLimitInput: String = "",
    val statementDayInput: String = "12",
    val dueRuleType: String = "AFTER_STATEMENT_DAYS",
    val dueRuleValueInput: String = "20",
    val limitSourceAccountId: Long? = null,
    val includeInAvailableCash: Boolean = true, val showOnAccountsPage: Boolean = true
) {
    val currencyLocked: Boolean get() = cashAccountId != null
}

data class CreditSourceOption(val id: Long, val label: String, val currency: Currency)

data class AccountEditUiState(
    val name: String = "",
    val note: String = "",
    val rows: List<CashAccountRowDraft> = emptyList(),
    val expectedRevision: Long? = null,
    val creditSources: List<CreditSourceOption> = emptyList(),
    val loaded: Boolean = false,
    val loadError: Boolean = false,
    val icon: AccountIcon = AccountIcon(),
    val iconImage: ByteArray? = null,
    val iconChanged: Boolean = false,
    val showDepositSummary: Boolean = true, val showInvestmentSummary: Boolean = true,
    val fieldErrors: Map<String, ErrorCode> = emptyMap()
)

class AccountEditViewModel(
    private val accountId: Long?,
    private val repository: OverviewRepository,
    private val commands: FinancialCommands,
    private val saved: SavedStateHandle
) : ViewModel() {
    val operationId: String = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also {
        saved["operationId"] = it
    }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) {
        saved["submission"] = it.name
    }
    val submission = session.state
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()

    init {
        if (!state.value.loaded) reload() else refreshCreditSources()
    }

    private fun restore(): AccountEditUiState {
        if (saved.get<Boolean>("loaded") != true) return AccountEditUiState()
        val keys = saved.get<ArrayList<String>>("rowKeys").orEmpty()
        return AccountEditUiState(name = saved["name"] ?: "", note = saved["note"] ?: "", rows = keys.map { key ->
            CashAccountRowDraft(key, saved["cashId-$key"], saved["cashName-$key"] ?: "",
                saved["cashNote-$key"] ?: "", Currency.of(requireNotNull(saved["currency-$key"])),
                saved["balance-$key"] ?: "", saved["before-$key"], saved["revision-$key"],
                BalanceAccountType.valueOf(saved["type-$key"] ?: BalanceAccountType.SAVINGS.name),
                saved["limit-$key"] ?: "", saved["statement-$key"] ?: "12",
                saved["dueType-$key"] ?: "AFTER_STATEMENT_DAYS", saved["dueValue-$key"] ?: "20",
                saved["source-$key"], saved["available-$key"] ?: true, saved["visible-$key"] ?: true)
        }, expectedRevision = saved["accountRevision"], loaded = true,
            icon = AccountIcon(AccountIconType.valueOf(saved["iconType"] ?: "SYMBOL"), saved["iconValue"] ?: "account_balance"),
            iconImage = saved["iconImage"], iconChanged = saved["iconChanged"] ?: false,
            showDepositSummary = saved["showDeposits"] ?: true, showInvestmentSummary = saved["showInvestments"] ?: true)
    }

    private fun persist(value: AccountEditUiState) {
        val activeKeys = value.rows.map { it.key }.toSet()
        state.value.rows.filter { it.key !in activeKeys }.forEach { row ->
            listOf("cashId", "cashName", "cashNote", "currency", "balance", "before", "revision", "type",
                "limit", "statement", "dueType", "dueValue", "source", "available", "visible").forEach {
                prefix -> saved.remove<Any>("$prefix-${row.key}")
            }
        }
        mutable.value = if (saved.get<Boolean>("validate") == true) value.copy(fieldErrors = validateAccountForm(value)) else value
        saved["loaded"] = value.loaded
        saved["name"] = value.name
        saved["note"] = value.note
        saved["showDeposits"] = value.showDepositSummary
        saved["showInvestments"] = value.showInvestmentSummary
        saved["iconType"] = value.icon.type.name
        saved["iconValue"] = value.icon.value
        saved["iconImage"] = value.iconImage
        saved["iconChanged"] = value.iconChanged
        saved["accountRevision"] = value.expectedRevision
        saved["rowKeys"] = ArrayList(value.rows.map { it.key })
        value.rows.forEach { row ->
            saved["cashId-${row.key}"] = row.cashAccountId
            saved["cashName-${row.key}"] = row.nameInput
            saved["cashNote-${row.key}"] = row.noteInput
            saved["currency-${row.key}"] = row.currency.code
            saved["balance-${row.key}"] = row.balanceInput
            saved["before-${row.key}"] = row.savedBalanceMinor
            saved["revision-${row.key}"] = row.expectedRevision
            saved["type-${row.key}"] = row.type.name
            saved["limit-${row.key}"] = row.creditLimitInput
            saved["statement-${row.key}"] = row.statementDayInput
            saved["dueType-${row.key}"] = row.dueRuleType
            saved["dueValue-${row.key}"] = row.dueRuleValueInput
            saved["source-${row.key}"] = row.limitSourceAccountId
            saved["available-${row.key}"] = row.includeInAvailableCash
            saved["visible-${row.key}"] = row.showOnAccountsPage
        }
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                val account = accountId?.let { id ->
                    snapshot.accounts.firstOrNull { it.id == id } ?: throw DomainException(ErrorCode.NOT_FOUND)
                }
                val accountNames = snapshot.accounts.associate { it.id to it.name }
                val sources = snapshot.cash.filter { it.account_id == accountId &&
                    it.type == BalanceAccountType.CREDIT &&
                    it.creditProfile?.limitSourceAccountId == null }.map {
                    CreditSourceOption(it.id, "${accountNames[it.account_id].orEmpty()} · ${it.name}", it.currency)
                }
                persist(AccountEditUiState(account?.name.orEmpty(), account?.note.orEmpty(),
                    snapshot.cash.filter { it.account_id == accountId }.map { cash ->
                        val profile = cash.creditProfile
                        CashAccountRowDraft(cash.id.toString(), cash.id, cash.name, cash.note, cash.currency,
                            R.format_units(cash.balance_minor, cash.currency.fraction_digits),
                            cash.balance_minor, cash.revision, cash.type,
                            profile?.creditLimitMinor?.let { R.format_units(it, cash.currency.fraction_digits) }.orEmpty(),
                            profile?.statementDay?.toString() ?: "12",
                            if (profile?.dueRule is CreditDueRule.FixedDayOfMonth) "FIXED_DAY_OF_MONTH" else "AFTER_STATEMENT_DAYS",
                            profile?.dueRule?.value?.toString() ?: "20", profile?.limitSourceAccountId,
                            cash.includeInAvailableCash, cash.showOnAccountsPage)
                    }, account?.revision, sources, true, icon = account?.icon ?: AccountIcon(),
                    showDepositSummary = account?.showDepositSummary ?: true, showInvestmentSummary = account?.showInvestmentSummary ?: true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loadError = true)
            }
        }
    }

    private fun refreshCreditSources() {
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                val accountNames = snapshot.accounts.associate { it.id to it.name }
                val sources = snapshot.cash.filter { it.account_id == accountId &&
                    it.type == BalanceAccountType.CREDIT &&
                    it.creditProfile?.limitSourceAccountId == null }.map {
                    CreditSourceOption(it.id, "${accountNames[it.account_id].orEmpty()} · ${it.name}", it.currency)
                }
                persist(state.value.copy(creditSources = sources))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The restored draft remains editable; a normal Reload can retry the source list.
            }
        }
    }

    fun changeSymbol(key: String) {
        if (submission.value.editable && key in AccountSymbols.keys)
            persist(state.value.copy(icon = AccountIcon(value = key), iconImage = null, iconChanged = true))
    }

    fun changeImage(bytes: ByteArray) {
        if (!submission.value.editable || bytes.size > AccountSymbols.MAX_IMAGE_BYTES) return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        persist(state.value.copy(icon = AccountIcon(AccountIconType.IMAGE, digest),
            iconImage = bytes.copyOf(), iconChanged = true))
    }

    fun changeName(value: String) {
        if (submission.value.editable) persist(state.value.copy(name = value))
    }

    fun changeNote(value: String) {
        if (submission.value.editable) persist(state.value.copy(note = value))
    }

    fun changeVisibility(deposits: Boolean? = null, investments: Boolean? = null) {
        if (submission.value.editable) persist(state.value.copy(
            showDepositSummary = deposits ?: state.value.showDepositSummary,
            showInvestmentSummary = investments ?: state.value.showInvestmentSummary))
    }

    fun changeRow(
        key: String,
        name: String? = null,
        note: String? = null,
        currency: Currency? = null,
        balance: String? = null,
        type: BalanceAccountType? = null,
        creditLimit: String? = null,
        statementDay: String? = null,
        dueRuleType: String? = null,
        dueRuleValue: String? = null,
        limitSourceAccountId: Long? = null,
        clearLimitSource: Boolean = false,
        includeInAvailableCash: Boolean? = null, showOnAccountsPage: Boolean? = null
    ) {
        if (!submission.value.editable) return
        persist(state.value.copy(rows = state.value.rows.map { row ->
            if (row.key != key) row else {
                val nextCurrency = if (row.currencyLocked) row.currency else currency ?: row.currency
                val nextName = when {
                    name != null -> name
                    else -> row.nameInput
                }
                val nextSource = when {
                    clearLimitSource -> null
                    limitSourceAccountId != null -> limitSourceAccountId
                    currency != null && state.value.creditSources.firstOrNull {
                        it.id == row.limitSourceAccountId
                    }?.currency != nextCurrency -> null
                    else -> row.limitSourceAccountId
                }
                row.copy(nameInput = nextName, noteInput = note ?: row.noteInput,
                    currency = nextCurrency, balanceInput = balance ?: row.balanceInput,
                    type = if (row.cashAccountId == null) type ?: row.type else row.type,
                    creditLimitInput = creditLimit ?: row.creditLimitInput,
                    statementDayInput = statementDay ?: row.statementDayInput,
                    dueRuleType = dueRuleType ?: row.dueRuleType,
                    dueRuleValueInput = dueRuleValue ?: row.dueRuleValueInput,
                    limitSourceAccountId = nextSource,
                    includeInAvailableCash = includeInAvailableCash ?: row.includeInAvailableCash,
                    showOnAccountsPage = showOnAccountsPage ?: row.showOnAccountsPage)
            }
        }))
    }

    fun addRow() {
        if (!submission.value.editable) return
        val currency = Currency.supported.first()
        persist(state.value.copy(rows = state.value.rows + CashAccountRowDraft(
            key = UUID.randomUUID().toString(), cashAccountId = null, nameInput = "",
            noteInput = "", currency = currency, balanceInput = "0")))
    }

    fun reorderRows(keys: List<String>) {
        if (!submission.value.editable) return
        val rows = state.value.rows
        if (keys.size != rows.size || keys.toSet() != rows.map { it.key }.toSet()) return
        val byKey = rows.associateBy { it.key }
        persist(state.value.copy(rows = keys.map { byKey.getValue(it) }))
    }

    fun removeRow(key: String) {
        if (!submission.value.editable) return
        persist(state.value.copy(rows = state.value.rows.filter { it.key != key || it.cashAccountId != null }))
    }

    fun submit() = session.submit {
        saved["validate"] = true
        persist(state.value)
        val input = state.value
        input.fieldErrors.values.firstOrNull()?.let { throw DomainException(it) }
        if (!input.loaded) throw DomainException(ErrorCode.NOT_FOUND)
        val changes = input.rows.mapIndexed { index, row ->
            val credit = if (row.type == BalanceAccountType.CREDIT) CreditAccountInput(
                creditLimitMinor = if (row.limitSourceAccountId == null)
                    R.parse_minor(row.creditLimitInput, row.currency, positive = false) else null,
                statementDay = row.statementDayInput.toIntOrNull() ?: throw DomainException(ErrorCode.INVALID_STATEMENT_DAY),
                dueRule = when (row.dueRuleType) {
                    "FIXED_DAY_OF_MONTH" -> CreditDueRule.FixedDayOfMonth(
                        row.dueRuleValueInput.toIntOrNull() ?: throw DomainException(ErrorCode.INVALID_DUE_RULE))
                    else -> CreditDueRule.AfterStatementDays(
                        row.dueRuleValueInput.toIntOrNull() ?: throw DomainException(ErrorCode.INVALID_DUE_RULE))
                },
                limitSourceAccountId = row.limitSourceAccountId
            ) else null
            CashBalanceChange(row.currency.code, R.parse_signed_minor(row.balanceInput, row.currency),
                row.expectedRevision, row.cashAccountId, row.nameInput, row.noteInput, row.type, credit, index.toLong(),
                row.includeInAvailableCash, row.showOnAccountsPage)
        }
        SaveAccount(operationId, accountId, input.expectedRevision, input.name, input.note, changes,
            if (input.iconChanged) AccountIconChange(input.icon, input.iconImage) else null,
            input.showDepositSummary, input.showInvestmentSummary)
    }

    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
