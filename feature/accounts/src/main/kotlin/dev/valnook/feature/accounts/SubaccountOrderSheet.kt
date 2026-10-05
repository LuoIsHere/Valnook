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
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentHeight = minOf(maxHeight * 0.7f,
                (96.dp + 64.dp * rows.size) * LocalDensity.current.fontScale.coerceAtLeast(1f))
            Column(Modifier.fillMaxWidth().height(contentHeight).padding(horizontal = Space.md)
                .testTag("subaccount-sort-sheet")) {
                Text(stringResource(R.string.account_order_children), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.account_order_cancel)) }
                    Button(onConfirm, Modifier.testTag("subaccount-order-confirm")) {
                        Text(stringResource(R.string.account_order_confirm))
                    }
                }
                ReorderList(orderedKeys.mapNotNull { key -> byKey[key]?.let { row ->
                    ReorderItem(key, row.nameInput.ifBlank { row.currency.code },
                        stringResource(if (row.type == BalanceAccountType.CREDIT) R.string.account_type_credit
                            else R.string.account_type_savings) + " · " + row.currency.code)
                } }, onReorder, stringResource(R.string.account_order_drag),
                    stringResource(R.string.account_order_up), stringResource(R.string.account_order_down),
                    Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
