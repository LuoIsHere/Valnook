package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.CreditLimitProgress
import dev.valnook.designsystem.FormDivider
import dev.valnook.designsystem.FormSection
import dev.valnook.designsystem.GlassCard
import dev.valnook.domain.calculation.CreditLimitCalculator
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.money.DecimalRules

/**
 * What the sub-account page shows between its header and the balance changes: a credit account's limit, then one card
 * of named lines (statement date, limit source, note). Lines without content are left out, and so is a card left empty.
 * Without [showNote] the note is left to the caller, as the wallet card shows it under the balance.
 */
@Composable internal fun CashDetailInfo(account: CashAccount, allAccounts: List<CashAccount>,
    creditSourceLabels: Map<Long, String>, modifier: Modifier = Modifier, showNote: Boolean = true) {
    val profile = account.creditProfile.takeIf { account.type == BalanceAccountType.CREDIT }
    val lines = buildList {
        if (profile != null) {
            add(InfoLine("statement", stringResource(R.string.cash_statement_day_label),
                stringResource(R.string.cash_statement_day_value, profile.statementDay)))
            profile.limitSourceAccountId?.let { sourceId ->
                add(InfoLine("source", stringResource(R.string.cash_limit_source_label),
                    creditSourceLabels[sourceId] ?: allAccounts.firstOrNull { it.id == sourceId }?.name ?: "#$sourceId"))
            }
        }
        if (showNote && account.note.isNotBlank()) add(InfoLine("note", stringResource(R.string.cash_note_label), account.note))
    }
    if (profile == null && lines.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (profile != null) CreditLimitCard(account, allAccounts)
        if (lines.isNotEmpty()) FormSection(null, Modifier.testTag("cash-info-card")) {
            lines.forEachIndexed { index, line ->
                if (index > 0) FormDivider()
                InfoRow(line)
            }
        }
    }
}

private class InfoLine(val key: String, val label: String, val value: String)

@Composable private fun InfoRow(line: InfoLine) {
    val style = MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics(mergeDescendants = true) {}
        .testTag("cash-info-${line.key}").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(line.label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(line.value, Modifier.weight(1f), style = style, textAlign = TextAlign.End)
    }
}

/** The bar and the three amounts as on the account page's card row, a little larger; an excess is said in red. */
@Composable private fun CreditLimitCard(account: CashAccount, allAccounts: List<CashAccount>) {
    val summary = runCatching { CreditLimitCalculator.calculate(account.id,
        if (allAccounts.any { it.id == account.id }) allAccounts else listOf(account)) }.getOrNull() ?: return
    val scale = account.currency.fraction_digits
    fun text(value: java.math.BigDecimal) = DecimalRules.format_display(value.longValueExact(), scale)
    GlassCard(Modifier.testTag("cash-limit-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CreditLimitProgress(summary.usedLimitMinor.toDouble(), summary.totalLimitMinor.toDouble())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val style = MaterialTheme.typography.bodyLarge
                LimitValue(stringResource(R.string.cash_limit_used), text(summary.usedLimitMinor), Modifier.weight(1f), style)
                LimitValue(stringResource(R.string.cash_limit_available), text(summary.availableLimitMinor), Modifier.weight(1f), style)
                LimitValue(stringResource(R.string.cash_limit_total), text(summary.totalLimitMinor), Modifier.weight(1f), style)
            }
            if (summary.overLimitMinor.signum() > 0) Text(stringResource(R.string.cash_over_limit,
                text(summary.overLimitMinor), account.currency.code), Modifier.testTag("cash-over-limit"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
