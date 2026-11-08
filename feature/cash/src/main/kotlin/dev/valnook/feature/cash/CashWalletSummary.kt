package dev.valnook.feature.cash

import dev.valnook.designsystem.CreditBalanceTag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.designsystem.CompactEditButton
import dev.valnook.designsystem.StatusTag
import dev.valnook.designsystem.TagTone
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditBillingFocus
import dev.valnook.domain.calculation.CreditBillingStatus
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashTrendPoint
import dev.valnook.domain.money.DecimalRules
import java.time.LocalDate

/**
 * The wallet card's sub-account, laid out like the sub-account page: the account it belongs to with the edit button,
 * the name with a credit account's state and next bill, the balance beside its sparkline and the note in plain text under
 * it, then the limit and details cards that page shows.
 */
@Composable fun CashWalletSummary(account: CashAccount, parentName: String, trend: List<CashTrendPoint>,
    onEdit: () -> Unit, allAccounts: List<CashAccount>, creditSourceLabels: Map<Long, String>,
    modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val credit = account.type == BalanceAccountType.CREDIT
    Column(modifier.fillMaxWidth().testTag("wallet-cash-summary")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(parentName, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompactEditButton(stringResource(R.string.cash_edit_balance), onEdit)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(account.name.ifBlank { account.currency.code }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            Text(stringResource(if (credit) R.string.cash_credit_account else R.string.cash_savings_account),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = .75f))
            val profile = account.creditProfile
            if (credit && profile != null) {
                CreditBalanceTag(account.balance_minor)
                val billing = CreditBillingCalendar.calculate(LocalDate.now(), profile.statementDay, profile.dueRule)
                StatusTag(creditBillingText(billing),
                    if (billing.daysRemaining <= BILL_ATTENTION_DAYS) TagTone.ATTENTION else TagTone.NEUTRAL,
                    Modifier.testTag("cash-billing-tag"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.headlineSmall
            Text(DecimalRules.format_display(account.balance_minor, account.currency.fraction_digits) + " " + account.currency.code,
                Modifier.weight(1f).testTag("wallet-cash-balance"), maxLines = 1,
                style = style.copy(fontFeatureSettings = "tnum"),
                autoSize = TextAutoSize.StepBased(12.sp, style.fontSize, .5.sp),
                color = if (!credit && account.balance_minor < 0) colors.error else colors.onSurface)
            CashTrendChart(trend, account, Modifier.width(72.dp), compact = true)
        }
        if (account.note.isNotBlank()) Text(account.note, Modifier.padding(top = 4.dp).testTag("wallet-cash-note"),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        CashDetailInfo(account, allAccounts, creditSourceLabels, Modifier.padding(top = 16.dp), showNote = false)
    }
}

@Composable internal fun creditBillingText(billing:CreditBillingStatus):String =
    dev.valnook.designsystem.creditBillingText(billing.daysRemaining, billing.focus==CreditBillingFocus.DUE)
