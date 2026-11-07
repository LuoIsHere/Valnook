package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "2026年9月" or "Sep 2026": the month as the language writes it. */
fun localMonthLabel(month: YearMonth, locale: java.util.Locale): String =
    month.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMM"), locale))

/**
 * The month between its two arrows, centered on its own line by default. [label] names the month; it defaults to
 * the numeric year and month.
 */
@Composable fun MonthPager(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    label: (YearMonth, java.util.Locale) -> String = { value, locale -> value.format(DateTimeFormatter.ofPattern("yyyy-MM", locale)) }) {
    val previous = stringResource(R.string.ledger_previous_month)
    val next = stringResource(R.string.ledger_next_month)
    // Read through the configuration so a language change recomposes the label.
    val locale = LocalConfiguration.current.locales[0]
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement) {
        MonthArrow(previous, false, { onChange(month.minusMonths(1)) }, month.year > 1)
        Text(label(month, locale),
            Modifier.padding(horizontal = 16.dp).testTag("ledger-month"), style = MaterialTheme.typography.titleSmall)
        MonthArrow(next, true, { onChange(month.plusMonths(1)) }, month.year < 9999)
    }
}

@Composable private fun MonthArrow(label: String, right: Boolean, click: () -> Unit, enabled: Boolean) {
    IconButton(click, Modifier.size(36.dp).testTag(if (right) "month-next" else "month-previous")
        .semantics { contentDescription = label }, enabled = enabled) {
        ChevronIcon(right)
    }
}
