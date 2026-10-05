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
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditBillingFocus
import dev.valnook.domain.calculation.CreditLimitCalculator
import androidx.compose.material3.LinearProgressIndicator
import java.math.RoundingMode
import java.time.LocalDate

/** Shared balance layout for the currency list and its ledger header. */
@Composable internal fun CashBalanceSummary(account:CashAccount,on_edit:(()->Unit)?,
    modifier:Modifier=Modifier,allAccounts:List<CashAccount> = emptyList(),
    creditSourceLabels:Map<Long,String> = emptyMap()) {
    Column(modifier.fillMaxWidth(),
        verticalArrangement=Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(account.name.ifBlank { account.currency.code },style=MaterialTheme.typography.titleMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(account.type==BalanceAccountType.CREDIT) stringResource(R.string.cash_credit_account)
                else stringResource(R.string.cash_savings_account),style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(DecimalRules.format_display(account.balance_minor,account.currency.fraction_digits)+" "+account.currency.code,
                style=MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings="tnum"),modifier=Modifier.weight(1f),
                color=if(account.type==BalanceAccountType.SAVINGS && account.balance_minor<0)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if(on_edit!=null) ActionButton(onClick=on_edit) {
                Text(stringResource(R.string.cash_edit_balance))
            }
        }
        if(account.note.isNotBlank()) Text(account.note,style=MaterialTheme.typography.bodySmall)
        if(account.type==BalanceAccountType.CREDIT) {
            val profile=account.creditProfile!!
            Text(if(account.balance_minor<0) stringResource(R.string.cash_amount_owed)
                else if(account.balance_minor>0) stringResource(R.string.cash_credit_balance)
                else stringResource(R.string.cash_no_debt),style=MaterialTheme.typography.labelMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            val summary=runCatching { CreditLimitCalculator.calculate(account.id,
                if(allAccounts.any{it.id==account.id}) allAccounts else listOf(account)) }.getOrNull()
            if(summary!=null) {
                LinearProgressIndicator(progress={ summary.usedLimitMinor.divide(summary.totalLimitMinor,4,
                    RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                val scale=account.currency.fraction_digits
                Text(stringResource(R.string.cash_limit_summary,
                    DecimalRules.format_display(summary.usedLimitMinor.longValueExact(),scale),
                    DecimalRules.format_display(summary.availableLimitMinor.longValueExact(),scale),
                    DecimalRules.format_display(summary.totalLimitMinor.longValueExact(),scale),account.currency.code),
                    style=MaterialTheme.typography.bodySmall)
                if(summary.overLimitMinor.signum()>0) Text(stringResource(R.string.cash_over_limit,
                    DecimalRules.format_display(summary.overLimitMinor.longValueExact(),scale),account.currency.code),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
            profile.limitSourceAccountId?.let { sourceId ->
                val source=allAccounts.firstOrNull{it.id==sourceId}
                Text(stringResource(R.string.cash_limit_source,
                    creditSourceLabels[sourceId] ?: source?.name ?: "#$sourceId"),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val billing=CreditBillingCalendar.calculate(LocalDate.now(),profile.statementDay,profile.dueRule)
            Text(stringResource(R.string.cash_statement_day,profile.statementDay),style=MaterialTheme.typography.bodySmall)
            Text(when {
                billing.daysRemaining==0L && billing.focus==CreditBillingFocus.STATEMENT -> stringResource(R.string.cash_statement_today)
                billing.daysRemaining==0L -> stringResource(R.string.cash_due_today)
                billing.focus==CreditBillingFocus.DUE -> stringResource(R.string.cash_due_in,billing.daysRemaining)
                else -> stringResource(R.string.cash_statement_in,billing.daysRemaining)
            },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
