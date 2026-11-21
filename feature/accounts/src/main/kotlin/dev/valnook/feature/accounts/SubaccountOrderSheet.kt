package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.BalanceAccountType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubaccountOrderSheet(rows: List<CashAccountRowDraft>, orderedKeys: List<String>,
    onReorder: (List<String>) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val byKey = rows.associateBy { it.key }
    ValnookBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The list runs on behind the navigation bar, so the sheet is that much taller for the same rows.
            val bottom = sheetBottomPadding()
            val contentHeight = minOf(maxHeight * 0.7f,
                (96.dp + 64.dp * rows.size) * LocalDensity.current.fontScale.coerceAtLeast(1f)) +
                bottom.calculateBottomPadding()
            Column(Modifier.fillMaxWidth().height(contentHeight).padding(horizontal = Space.md)
                .testTag("subaccount-sort-sheet")) {
                SheetHeader(stringResource(R.string.account_order_children)) {
                    TextButton(onDismiss) { Text(stringResource(R.string.account_order_cancel)) }
                    PrimaryButton(onConfirm, Modifier.testTag("subaccount-order-confirm")) {
                        Text(stringResource(R.string.account_order_confirm))
                    }
                }
                ReorderList(orderedKeys.mapNotNull { key -> byKey[key]?.let { row ->
                    ReorderItem(key, row.nameInput.ifBlank { row.currency.code },
                        stringResource(if (row.type == BalanceAccountType.CREDIT) R.string.account_type_credit
                            else R.string.account_type_savings) + " · " + row.currency.code)
                } }, onReorder, stringResource(R.string.account_order_drag),
                    stringResource(R.string.account_order_up), stringResource(R.string.account_order_down),
                    Modifier.fillMaxWidth().weight(1f), contentPadding = bottom)
            }
        }
    }
}
