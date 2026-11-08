package dev.valnook.feature.cash

import dev.valnook.designsystem.CreditBalanceTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.core.designsystem.R as CoreR
import dev.valnook.designsystem.CreditLimitProgress
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.StatusTag
import dev.valnook.designsystem.TagTone
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditLimitCalculator
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashTrendPoint
import dev.valnook.domain.money.DecimalRules
import java.time.LocalDate
import kotlin.math.roundToInt

/** A bill this close gets the attention color. */
internal const val BILL_ATTENTION_DAYS = 3L

@Composable internal fun CashSection(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard(content = content)
    }
}

@Composable internal fun CashRowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
}

@Composable private fun AccountName(account: CashAccount, modifier: Modifier = Modifier) {
    Text(account.name.ifBlank { account.currency.code }, modifier, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun AccountAmount(account: CashAccount, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleLarge
    val text = buildAnnotatedString {
        append(DecimalRules.format_display(account.balance_minor, account.currency.fraction_digits))
        append(" ")
        withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = colors.onSurfaceVariant)) {
            append(account.currency.code)
        }
    }
    Text(text, modifier, color = if (account.type == BalanceAccountType.SAVINGS && account.balance_minor < 0)
        colors.error else colors.onSurface, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1,
        autoSize = TextAutoSize.StepBased(10.sp, style.fontSize, .5.sp))
}

@Composable private fun Modifier.openRow(account: CashAccount, on_open: () -> Unit): Modifier {
    val label = stringResource(CoreR.string.cash_changes) + " · " + account.name
    return testTag("cash-row-${account.id}").fillMaxWidth().clickable(onClick = on_open)
        .semantics { contentDescription = label }.padding(12.dp)
}

@Composable internal fun SavingsAccountRow(account: CashAccount, trend: List<CashTrendPoint>, on_open: () -> Unit) {
    Column(Modifier.openRow(account, on_open), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AccountName(account)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountAmount(account, Modifier.weight(1f))
            CashTrendChart(trend, account, Modifier.width(72.dp), compact = true)
        }
        if (account.note.isNotBlank()) Text(account.note, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The name, state, amount and note are always visible; [expansion] from 0 to 1 opens the limits and bill details below.
 */
@Composable internal fun CreditAccountRow(account: CashAccount, allAccounts: List<CashAccount>,
    creditSourceLabels: Map<Long, String>, trend: List<CashTrendPoint>, expansion: () -> Float, on_open: () -> Unit) {
    val profile = account.creditProfile!!
    val billing = CreditBillingCalendar.calculate(LocalDate.now(), profile.statementDay, profile.dueRule)
    Column(Modifier.openRow(account, on_open), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            AccountName(account)
            CreditBalanceTag(account.balance_minor)
            Spacer(Modifier.weight(1f))
            StatusTag(creditBillingText(billing),
                if (billing.daysRemaining <= BILL_ATTENTION_DAYS) TagTone.ATTENTION else TagTone.NEUTRAL)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccountAmount(account, Modifier.weight(1f))
            CashTrendChart(trend, account, Modifier.width(72.dp), compact = true)
        }
        if (account.note.isNotBlank()) Text(account.note, Modifier.testTag("cash-credit-note-${account.id}"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CreditDetails(account, allAccounts, creditSourceLabels, profile.statementDay, profile.limitSourceAccountId, expansion)
    }
}

/** Stays in the layout while closed, so a screen reader still reads it as part of the row. */
@Composable private fun CreditDetails(account: CashAccount, allAccounts: List<CashAccount>, creditSourceLabels: Map<Long, String>,
    statementDay: Int, limitSourceId: Long?, expansion: () -> Float) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("cash-credit-details-${account.id}").clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(placeable.width, (placeable.height * expansion().coerceIn(0f, 1f)).roundToInt()) { placeable.place(0, 0) }
        }
        // The text appears only once there is room to read it.
        .graphicsLayer { alpha = (expansion() * 1.6f - .6f).coerceIn(0f, 1f) }
        .padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val summary = runCatching { CreditLimitCalculator.calculate(account.id,
            if (allAccounts.any { it.id == account.id }) allAccounts else listOf(account)) }.getOrNull()
        if (summary != null) {
            val scale = account.currency.fraction_digits
            CreditLimitProgress(summary.usedLimitMinor.toDouble(), summary.totalLimitMinor.toDouble())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LimitValue(stringResource(R.string.cash_limit_used), DecimalRules.format_display(summary.usedLimitMinor.longValueExact(), scale), Modifier.weight(1f))
                LimitValue(stringResource(R.string.cash_limit_available), DecimalRules.format_display(summary.availableLimitMinor.longValueExact(), scale), Modifier.weight(1f))
                LimitValue(stringResource(R.string.cash_limit_total), DecimalRules.format_display(summary.totalLimitMinor.longValueExact(), scale), Modifier.weight(1f))
            }
            if (summary.overLimitMinor.signum() > 0) Text(stringResource(R.string.cash_over_limit,
                DecimalRules.format_display(summary.overLimitMinor.longValueExact(), scale), account.currency.code),
                style = small, color = MaterialTheme.colorScheme.error)
        }
        limitSourceId?.let { sourceId ->
            val source = allAccounts.firstOrNull { it.id == sourceId }
            Text(stringResource(R.string.cash_limit_source, creditSourceLabels[sourceId] ?: source?.name ?: "#$sourceId"),
                style = small, color = muted)
        }
        Text(stringResource(R.string.cash_statement_day, statementDay), style = small, color = muted)
    }
}

@Composable internal fun LimitValue(label: String, value: String, modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = style.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), maxLines = 1,
            autoSize = TextAutoSize.StepBased(8.sp, style.fontSize, .25.sp))
    }
}
