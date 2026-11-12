package dev.valnook.feature.bills

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillRepository
import dev.valnook.domain.repository.DeleteBill
import dev.valnook.domain.repository.FinancialCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class BillDetailState(
    val loaded: Boolean = false,
    val bill: Bill? = null,
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: Map<Long, BillAccountChoice> = emptyMap(),
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val error: ErrorCode? = null,
)

class BillDetailViewModel(billId: Long, bills: BillRepository, private val commands: FinancialCommands) : ViewModel() {
    private val action = MutableStateFlow(Triple(false, false, null as ErrorCode?))
    val state: StateFlow<BillDetailState> = combine(bills.observeBill(billId), bills.observeCategories(),
        bills.observeAccountChoices(), action) { bill, categories, accounts, (deleting, deleted, error) ->
        BillDetailState(true, bill, categories.associateBy { it.id }, accounts.associateBy { it.cashAccountId }, deleting, deleted, error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillDetailState())

    /** Deletes the bill; its accounts move back by what it moved them. */
    fun delete() {
        val bill = state.value.bill ?: return
        if (action.value.first) return
        action.value = Triple(true, false, null)
        viewModelScope.launch {
            try {
                commands.execute(DeleteBill(UUID.randomUUID().toString(), bill.id, bill.revision))
                action.value = Triple(false, true, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { action.value = Triple(false, false, error.code) }
            catch (_: Exception) { action.value = Triple(false, false, ErrorCode.FORMAT) }
        }
    }
}

@Composable fun BillDetailScreen(vm: BillDetailViewModel, onEdit: (Long) -> Unit, onCopy: (Long) -> Unit, onDeleted: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    if (!state.loaded) { PageLoading(); return }
    val bill = state.bill
    if (bill == null) { if (!state.deleted) EmptyState(stringResource(R.string.bill_unavailable)); return }
    var confirming by remember { mutableStateOf(false) }
    val rows = detailRows(bill, state)
    val title = if (bill.type == BillType.TRANSFER) transferTitle(bill, state.accounts)
        else categoryName((bill.subCategoryId ?: bill.categoryId)?.let(state.categories::get))
    RecordDetailLayout(title, rows) {
        ErrorMessage(state.error?.name)
        ActionButton({ onEdit(bill.id) }, Modifier.fillMaxWidth().testTag("bill-edit")) { Text(stringResource(R.string.bill_edit)) }
        ActionButton({ onCopy(bill.id) }, Modifier.fillMaxWidth().testTag("bill-copy")) { Text(stringResource(R.string.bill_copy)) }
        ActionButton({ confirming = true }, Modifier.fillMaxWidth().testTag("bill-delete"), enabled = !state.deleting, destructive = true) {
            Text(stringResource(R.string.bill_delete))
        }
    }
    if (confirming) AlertDialog({ confirming = false },
        confirmButton = { TextButton({ confirming = false; vm.delete() }, Modifier.testTag("bill-delete-confirm")) {
            Text(stringResource(R.string.bill_delete)) } },
        dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.bill_cancel)) } },
        title = { Text(stringResource(R.string.bill_delete_title)) },
        text = { Text(stringResource(R.string.bill_delete_message)) })
}

@Composable private fun detailRows(bill: Bill, state: BillDetailState): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    val time = Instant.ofEpochMilli(bill.occurredAtMs).atZone(ZoneId.systemDefault())
    val money = { minor: Long, currency: Currency -> "${plainAmount(minor, currency.fraction_digits)} ${currency.code}" }
    rows += stringResource(R.string.bill_type) to stringResource(typeLabel(bill.type))
    if (bill.type == BillType.TRANSFER) {
        val from = sideName(bill.cashAccountId, state.accounts, bill.transferKind == TransferKind.FROM_EXTERNAL, bill.counterparty)
        val to = sideName(bill.toCashAccountId, state.accounts, bill.transferKind == TransferKind.TO_EXTERNAL, bill.counterparty)
        rows += stringResource(R.string.bill_transfer_from) to from
        rows += stringResource(R.string.bill_transfer_to) to to
        if (bill.transferKind == TransferKind.FROM_EXTERNAL) rows += stringResource(R.string.bill_amount) to money(bill.toAmountMinor ?: bill.amountMinor, bill.toCurrency ?: bill.currency)
        else {
            rows += stringResource(R.string.bill_transfer_out_amount) to money(bill.amountMinor, bill.accountCurrency)
            val received = bill.toAmountMinor
            val receivedCurrency = bill.toCurrency
            if (bill.transferKind == TransferKind.INTERNAL && receivedCurrency != null && received != null)
                rows += stringResource(R.string.bill_transfer_in_amount) to money(received, receivedCurrency)
            if (bill.feeMinor > 0) rows += stringResource(R.string.bill_fee) to money(bill.feeMinor, bill.accountCurrency)
        }
    } else {
        rows += stringResource(R.string.bill_amount) to money(bill.amountMinor, bill.currency)
        if (bill.discountMinor > 0) {
            rows += stringResource(R.string.bill_discount) to money(bill.discountMinor, bill.currency)
            rows += stringResource(R.string.bill_paid) to money(bill.paidMinor, bill.currency)
        }
        rows += stringResource(R.string.bill_account) to sideName(bill.cashAccountId, state.accounts, false, "")
        val category = bill.categoryId?.let(state.categories::get)
        val sub = bill.subCategoryId?.let(state.categories::get)
        rows += stringResource(R.string.bill_category) to (categoryName(category) + (sub?.let { " › " + categoryName(it) } ?: ""))
        rows += stringResource(R.string.bill_count_in_stats) to stringResource(if (bill.countInStats) R.string.bill_yes else R.string.bill_no)
    }
    val crossed = bill.rateSource != BillRateSource.SAME
    if (crossed) {
        val pairTo = if (bill.type == BillType.TRANSFER) bill.toCurrency ?: bill.accountCurrency else bill.accountCurrency
        val pairFrom = if (bill.type == BillType.TRANSFER) bill.accountCurrency else bill.currency
        rows += stringResource(R.string.bill_rate) to "1 ${pairFrom.code} = ${bill.rate.stripTrailingZeros().toPlainString()} ${pairTo.code}"
        if (bill.type != BillType.TRANSFER) rows += stringResource(R.string.bill_account_amount, bill.accountCurrency.code) to
            money(bill.accountAmountMinor, bill.accountCurrency)
        rows += stringResource(R.string.bill_rate_source) to stringResource(when (bill.rateSource) {
            BillRateSource.AUTO -> R.string.bill_rate_source_auto
            BillRateSource.MANUAL -> R.string.bill_rate_source_manual
            BillRateSource.DERIVED -> R.string.bill_rate_source_derived
            else -> R.string.bill_rate_source_once
        })
    }
    rows += stringResource(R.string.bill_time) to time.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE) + " " +
        time.toLocalTime().format(rememberClockFormatter())
    rows += stringResource(R.string.bill_note) to bill.note.ifBlank { stringResource(R.string.bill_no_note) }
    return rows
}
