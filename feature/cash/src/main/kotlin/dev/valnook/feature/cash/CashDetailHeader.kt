package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.CollapsingBalanceHeader
import dev.valnook.designsystem.CompactEditButton
import dev.valnook.designsystem.HeaderCollapse
import dev.valnook.designsystem.StatusTag
import dev.valnook.designsystem.TagTone
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashTrendPoint
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The title bar's delete button (48dp) and its margin, plus a small gap. */
private val COLLAPSED_END_INSET = 60.dp

/**
 * The sub-account's balance with its edit button and trend, collapsing like the account page. Above the balance a
 * credit account says whether it owes and when the next bill is; a savings account says it is the current balance.
 * A finger on the chart shows that day's date and value instead.
 */
@Composable internal fun CashDetailHeader(collapse: HeaderCollapse, account: CashAccount, points: List<CashTrendPoint>,
    onEdit: () -> Unit, onTitleReserve: (Int) -> Unit) {
    var selectedIndex by remember(account.id, points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let(points::getOrNull)
    val locale = LocalConfiguration.current.locales[0]
    val amount = rememberCashAmount(account)
    val shown = if (selected != null) selected.balanceMinor else BigInteger.valueOf(account.balance_minor)
    val credit = account.type == BalanceAccountType.CREDIT
    // As in the account list: an overdrawn savings account is an error, a credit account owing is its normal state.
    val color = if (!credit && shown != null && shown.signum() < 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    val label = when {
        selected != null -> selected.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        !credit -> stringResource(R.string.cash_current_balance)
        account.balance_minor < 0 -> stringResource(R.string.cash_amount_owed)
        account.balance_minor > 0 -> stringResource(R.string.cash_credit_balance)
        else -> stringResource(R.string.cash_no_debt)
    }
    CollapsingBalanceHeader(collapse, amount(shown), color, onTitleReserve,
        Modifier.testTag("cash-detail-header"), Modifier.testTag("cash-trend-value"), COLLAPSED_END_INSET,
        trailing = { CompactEditButton(stringResource(R.string.cash_edit_balance), onEdit) },
        top = { fading ->
            Row(fading.fillMaxWidth().heightIn(min = 24.dp).semantics(mergeDescendants = true) {}.testTag("cash-trend-date"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val profile = account.creditProfile
                if (selected == null && credit && profile != null) {
                    val billing = CreditBillingCalendar.calculate(LocalDate.now(), profile.statementDay, profile.dueRule)
                    StatusTag(creditBillingText(billing),
                        if (billing.daysRemaining <= BILL_ATTENTION_DAYS) TagTone.ATTENTION else TagTone.NEUTRAL,
                        Modifier.testTag("cash-billing-tag"))
                }
            }
        }) { chartModifier ->
        CashTrendLine(points, account, selectedIndex, { index ->
            // Ignored while closing: the chart is on its way out and may sit under the finger.
            selectedIndex = index.takeIf { collapse.progress <= 0f }
        }, compact = false, labelAlpha = { (1f - collapse.progress * 7f).coerceAtLeast(0f) }, modifier = chartModifier)
    }
}
