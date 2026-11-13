package dev.valnook.feature.cash

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R as CoreR
import dev.valnook.designsystem.FormDivider
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.LocalTagPalette
import dev.valnook.designsystem.MonthPager
import dev.valnook.designsystem.localMonthLabel
import dev.valnook.designsystem.rememberClockFormatter
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.money.DecimalRules
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "9月25日周五" or "Fri, Sep 25". */
internal fun cashDayLabel(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale))

internal fun CashEntry.localDate(): LocalDate = Instant.ofEpochMilli(occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** The section's name with the month arrows on the same line. */
@Composable internal fun CashChangesHeader(month: YearMonth, onMonth: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("cash-changes-header"), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(CoreR.string.cash_changes), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        MonthPager(month, onMonth, Modifier, Arrangement.End, ::localMonthLabel)
    }
}

/** One day: its date, then its changes in one card with faint lines between them. */
@Composable fun CashDayGroup(date: LocalDate, entries: List<CashEntry>, onEntry: (Long) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val clock = rememberClockFormatter()
    Column(Modifier.fillMaxWidth().testTag("cash-day-$date")) {
        Text(cashDayLabel(date, locale), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard {
            entries.forEachIndexed { index, entry ->
                if (index > 0) FormDivider()
                CashLedgerRow(entry, clock) { onEntry(entry.id) }
            }
        }
    }
}

/** Made once rather than for every row. */
private val WHITESPACE = Regex("\\s+")

/**
 * The note (or, without one, where the change came from; a bill's entry is titled as the bill) over its time; the change over the balance it left, both in
 * the time's small type for the latter. An increase is green like the overpayment tag.
 */
@Composable internal fun CashLedgerRow(entry: CashEntry, clock: DateTimeFormatter, onOpen: () -> Unit) {
    val note = entry.note.trim().replace(WHITESPACE, " ")
    val time = Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault()).format(clock)
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val large = MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen)
        .testTag("cash-entry-${entry.id}").padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.bill?.let { cash_bill_title(it) } ?: note.ifEmpty { cash_source_label(entry) },
                style = large, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, style = small, color = muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cash_change_text(entry), style = large.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                color = if (entry.delta_minor > 0) LocalTagPalette.current.positive.content else MaterialTheme.colorScheme.onSurface)
            entry.balanceAfterMinor?.let { after ->
                val text = DecimalRules.format_display(after, entry.currency.fraction_digits)
                val description = stringResource(R.string.cash_balance_after, text)
                Text(text, Modifier.semantics { contentDescription = description }.testTag("cash-entry-balance-${entry.id}"),
                    style = small.copy(fontFeatureSettings = "tnum"), color = muted, maxLines = 1)
            }
        }
    }
}

@Composable fun CashMonthEmpty(modifier: Modifier = Modifier) {
    GlassCard(modifier.testTag("cash-month-empty")) {
        Text(stringResource(R.string.cash_month_empty), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}
