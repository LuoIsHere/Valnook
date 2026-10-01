package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.ActionButton
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.Currency
import dev.valnook.domain.money.DecimalRules

/** Shared balance layout for the currency list and its ledger header. */
@Composable internal fun CashBalanceSummary(currency:Currency,balance_minor:Long,on_edit:(()->Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start=Space.md,top=Space.md,end=Space.md,bottom=Space.sm),
        verticalArrangement=Arrangement.spacedBy(Space.sm)) {
        Text(currency.code+" · "+currency.name,style=MaterialTheme.typography.titleMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(DecimalRules.format_display(balance_minor,currency.fraction_digits),
                modifier=Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings="tnum"))
            if(on_edit!=null)ActionButton(onClick=on_edit,modifier=Modifier.widthIn(max=144.dp)) {
                Text(stringResource(R.string.cash_set))
            }
        }
    }
}
