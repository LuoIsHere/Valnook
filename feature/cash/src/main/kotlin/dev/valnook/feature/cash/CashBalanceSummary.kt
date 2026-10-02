package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.valnook.designsystem.ActionButton
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.money.DecimalRules

/** Shared balance layout for the currency list and its ledger header. */
@Composable internal fun CashBalanceSummary(account:CashAccount,on_edit:(()->Unit)?,modifier:Modifier=Modifier) {
    Column(modifier.fillMaxWidth(),
        verticalArrangement=Arrangement.spacedBy(Space.sm)) {
        Text(account.name.ifBlank { account.currency.code },style=MaterialTheme.typography.titleMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(DecimalRules.format_display(account.balance_minor,account.currency.fraction_digits)+" "+account.currency.code,
                style=MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings="tnum"),modifier=Modifier.weight(1f),
                color=if(account.balance_minor<0)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if(on_edit!=null) ActionButton(onClick=on_edit) {
                Text(stringResource(R.string.cash_edit_balance))
            }
        }
        if(account.note.isNotBlank()) Text(account.note,style=MaterialTheme.typography.bodySmall)
    }
}
