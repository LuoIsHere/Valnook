package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*

@Composable
internal fun AccountIconEditor(state: AccountEditUiState, enabled: Boolean,
    onSymbol: (String) -> Unit, onPickImage: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountAvatar(state.icon.symbol, state.icon.imageKey, size = 64.dp, preview = state.iconImage,
            modifier = Modifier.testTag("account-icon-preview"))
        TextButton({ expanded = true }, enabled = enabled, modifier = Modifier.testTag("account-icon-edit")) {
            Text(stringResource(R.string.account_icon_change))
        }
    }
    AnimatedGlassDialog(expanded, { expanded = false }) {
        GlassCard(Modifier.testTag("account-icon-dialog")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_icon_title), style = MaterialTheme.typography.titleLarge)
                Button({ expanded = false; onPickImage() }, enabled = expanded && enabled,
                    modifier = Modifier.fillMaxWidth().testTag("account-icon-photo")) {
                    Text(stringResource(R.string.account_icon_photo))
                }
                val symbols = AccountSymbols.keys.toList()
                val labels = stringResource(R.string.account_icon_labels).split("|")
                LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * .42f).dp)) {
                    itemsIndexed(symbols, key = { _, symbol -> symbol }) { index, symbol ->
                        val selected = state.icon.type == AccountIconType.SYMBOL && state.icon.value == symbol
                        Box(Modifier.padding(3.dp).sizeIn(minHeight = 56.dp).clip(CircleShape)
                            .testTag("account-symbol-$symbol")
                            .selectable(selected, enabled = expanded && enabled, role = Role.RadioButton) {
                                onSymbol(symbol); expanded = false
                            }.semantics { contentDescription = labels.getOrElse(index) { symbol } },
                            contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow) {
                                Box(Modifier.padding(7.dp)) { AccountAvatar(symbol, size = 34.dp) }
                            }
                            if (selected) Text("✓", Modifier.align(Alignment.BottomEnd), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ onSymbol("account_balance"); expanded = false }, enabled = expanded && enabled) {
                        Text(stringResource(R.string.account_icon_reset))
                    }
                    TextButton({ expanded = false }) { Text(stringResource(R.string.account_order_cancel)) }
                }
            }
        }
    }
}
