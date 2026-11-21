package dev.valnook.feature.cash

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FormDivider
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.MonthPager
import dev.valnook.designsystem.RowChevron
import dev.valnook.designsystem.creditBillingText
import dev.valnook.designsystem.localMonthLabel
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditCycle
import dev.valnook.domain.calculation.CreditCycleSummary
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CreditAccountProfile
import dev.valnook.domain.model.CreditCycleTotals
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.PagedCashRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import dev.valnook.core.designsystem.R as CoreR

/** Where the wallet's card summary reads billing cycles from; the sub-account page has its view model instead. */
val LocalCreditCycles = staticCompositionLocalOf<PagedCashRepository?> { null }

/**
 * The cycle [today] is in with its bills, and the statement before it with what it left owing (the balance just
 * before this cycle began). Emits again whenever a bill or the balance changes.
 */
fun PagedCashRepository.observeCreditCycleSummary(cashAccountId: Long, profile: CreditAccountProfile,
    today: LocalDate, zone: ZoneId): Flow<CreditCycleSummary> {
    val current = CreditBillingCalendar.currentCycle(today, profile)
    val previous = CreditBillingCalendar.cycle(current.month.minusMonths(1), profile)
    val period = current.period(zone)
    return combine(observeCycleTotals(cashAccountId, period), observeBalanceBefore(cashAccountId, period.startMs)) { totals, balance ->
        CreditCycleSummary.of(current, totals, previous, balance)
    }
}

/** "11/13–12/12", with the years when the cycle is not in this year. */
internal fun cycleRangeLabel(cycle: CreditCycle, locale: Locale, today: LocalDate = LocalDate.now()): String {
    val skeleton = if (cycle.start.year == today.year && cycle.end.year == today.year) "Md" else "yMd"
    val format = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    return "${cycle.start.format(format)}–${cycle.end.format(format)}"
}

private fun amount(minor: Long, account: CashAccount) = DecimalRules.format_display(minor, account.currency.fraction_digits)

/**
 * The credit card's billing cycles: this one so far (spent less refunds, refunds, repayments, when it closes) and
 * the last statement (what it left owing, paid since, still to pay). [onOpen] shows a cycle's changes; without it
 * (the wallet) the parts are not tappable.
 */
@Composable internal fun CreditCycleCard(summary: CreditCycleSummary, account: CashAccount,
    onOpen: ((YearMonth) -> Unit)?, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val locale = LocalConfiguration.current.locales[0]
    val totals = summary.currentTotals
    GlassCard(modifier.testTag("cash-cycle-card")) {
        CyclePart(stringResource(R.string.cash_cycle_current, cycleRangeLabel(summary.current, locale, today)),
            creditBillingText(ChronoUnit.DAYS.between(today, summary.current.statement), false),
            onOpen?.let { { it(summary.current.month) } }, "cash-cycle-current") {
            CycleValue(stringResource(R.string.cash_cycle_spent), amount(totals.netSpentMinor, account), Modifier.weight(1f),
                "cash-cycle-spent")
            CycleValue(stringResource(R.string.cash_cycle_refunded), amount(totals.refundedMinor, account), Modifier.weight(1f),
                "cash-cycle-refunded")
            CycleValue(stringResource(R.string.cash_cycle_repaid), amount(totals.repaidMinor, account), Modifier.weight(1f),
                "cash-cycle-repaid")
        }
        FormDivider()
        val owed = summary.previousOwedMinor
        val remaining = summary.previousRemainingMinor
        val state = when {
            owed == null -> ""
            owed == 0L -> stringResource(R.string.cash_cycle_nothing_due)
            remaining == 0L -> stringResource(R.string.cash_cycle_paid_off)
            summary.previous.due >= today -> creditBillingText(ChronoUnit.DAYS.between(today, summary.previous.due), true)
            else -> stringResource(R.string.cash_cycle_overdue)
        }
        CyclePart(stringResource(R.string.cash_cycle_previous, cycleRangeLabel(summary.previous, locale, today)), state,
            onOpen?.let { { it(summary.previous.month) } }, "cash-cycle-previous") {
            if (owed == null || remaining == null) Text(stringResource(R.string.cash_cycle_no_record),
                Modifier.testTag("cash-cycle-no-record"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                CycleValue(stringResource(R.string.cash_cycle_owed), amount(owed, account), Modifier.weight(1f), "cash-cycle-owed")
                CycleValue(stringResource(R.string.cash_cycle_paid), amount(summary.previousRepaidMinor, account),
                    Modifier.weight(1f), "cash-cycle-paid")
                CycleValue(stringResource(R.string.cash_cycle_remaining), amount(remaining, account), Modifier.weight(1f),
                    "cash-cycle-remaining")
            }
        }
        Text(stringResource(R.string.cash_cycle_estimate), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A cycle's title and state over its three amounts; tapping it, when allowed, shows that cycle's changes. */
@Composable private fun CyclePart(title: String, state: String, onClick: (() -> Unit)?, tag: String,
    values: @Composable RowScope.() -> Unit) {
    val clickable = if (onClick == null) Modifier
        else Modifier.clickable(onClickLabel = stringResource(R.string.cash_cycle_open), role = Role.Button, onClick = onClick)
    Column(Modifier.fillMaxWidth().then(clickable).testTag(tag).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The dates may wrap under large text; they are never cut short.
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            if (state.isNotEmpty()) Text(state, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (onClick != null) RowChevron()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = values)
    }
}

/** Read out as one: the label with its amount. */
@Composable private fun CycleValue(label: String, value: String, modifier: Modifier, tag: String) =
    LimitValue(label, value, modifier.semantics(mergeDescendants = true) {}.testTag(tag), MaterialTheme.typography.bodyLarge)

/** The wallet's copy of the card: read through [LocalCreditCycles], not tappable. Nothing without a statement day. */
@Composable internal fun WalletCreditCycleCard(account: CashAccount, modifier: Modifier = Modifier) {
    val profile = account.creditProfile ?: return
    val source = LocalCreditCycles.current ?: return
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val flow = remember(source, account.id, profile, today, zone) {
        source.observeCreditCycleSummary(account.id, profile, today, zone).catch { }
    }
    val summary by flow.collectAsState(null)
    summary?.let { CreditCycleCard(it, account, null, modifier, today) }
}

/** By cycle or by month, as two capsules; the chosen one is filled. */
@Composable internal fun LedgerViewSwitch(view: CashLedgerView, onView: (CashLedgerView) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.testTag("cash-ledger-view"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ViewPill(stringResource(R.string.cash_view_cycle), view == CashLedgerView.CYCLE, "cash-view-cycle") { onView(CashLedgerView.CYCLE) }
        ViewPill(stringResource(R.string.cash_view_month), view == CashLedgerView.MONTH, "cash-view-month") { onView(CashLedgerView.MONTH) }
    }
}

@Composable private fun ViewPill(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50))
        .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
        .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
        .padding(horizontal = 12.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant)
    }
}

/**
 * The changes' heading for a credit sub-account: the name with the by-cycle/by-month switch, then the arrows paging
 * cycles (named by their dates, the current one marked) or months.
 */
@Composable internal fun CreditChangesHeader(ledger: CashLedgerState.Ready, profile: CreditAccountProfile,
    onView: (CashLedgerView) -> Unit, onCycle: (YearMonth) -> Unit, onMonth: (YearMonth) -> Unit,
    modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    val cycle = ledger.cycle
    Column(modifier.fillMaxWidth().testTag("cash-changes-header"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(CoreR.string.cash_changes), Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            LedgerViewSwitch(if (cycle != null) CashLedgerView.CYCLE else CashLedgerView.MONTH, onView)
        }
        if (cycle == null) MonthPager(ledger.month, onMonth, Modifier.fillMaxWidth(), Arrangement.Center,
            ::localMonthLabel)
        else {
            val currentRange = ledger.currentCycle?.let { cycleRangeLabel(it, LocalConfiguration.current.locales[0], today) }
            val currentLabel = currentRange?.let { stringResource(R.string.cash_cycle_this_one, it) }
            MonthPager(cycle.month, onCycle, Modifier.fillMaxWidth(), Arrangement.Center) { month, locale ->
                if (month == ledger.currentCycle?.month && currentLabel != null) currentLabel
                else cycleRangeLabel(CreditBillingCalendar.cycle(month, profile), locale, today)
            }
        }
    }
}

/** The shown cycle's spending (before refunds), refunds and repayments, above its changes. */
@Composable internal fun CycleTotalsRow(totals: CreditCycleTotals, account: CashAccount, modifier: Modifier = Modifier) {
    GlassCard(modifier.testTag("cash-cycle-totals")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CycleValue(stringResource(R.string.cash_cycle_spending), amount(totals.spentMinor, account), Modifier.weight(1f),
                "cash-cycle-totals-spent")
            CycleValue(stringResource(R.string.cash_cycle_refunded), amount(totals.refundedMinor, account), Modifier.weight(1f),
                "cash-cycle-totals-refunded")
            CycleValue(stringResource(R.string.cash_cycle_repaid), amount(totals.repaidMinor, account), Modifier.weight(1f),
                "cash-cycle-totals-repaid")
        }
    }
}
