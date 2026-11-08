package dev.valnook.feature.deposits

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.LocalTagPalette
import dev.valnook.designsystem.StatusTag
import dev.valnook.designsystem.TagTone
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The quick terms offered when opening a deposit, in months. */
internal val TERM_MONTHS = listOf(3, 6, 12, 24, 36)

/** Whole months from [start] to [end], or null when the end is not a whole number of months away. */
internal fun wholeMonths(start: LocalDate, end: LocalDate): Int? {
    // From a month's end the count falls one short: 31 October + 6 months is 30 April, five months by the calendar.
    val months = ChronoUnit.MONTHS.between(start, end)
    return listOf(months, months + 1).firstOrNull { it > 0 && start.plusMonths(it) == end }?.toInt()
}

/** "3 个月", "1 年" or "92 天": whole years or months when the dates allow, days otherwise. */
@Composable internal fun termLength(start: LocalDate, end: LocalDate): String {
    val months = wholeMonths(start, end)
    return when {
        months != null && months % 12 == 0 -> pluralStringResource(R.plurals.deposit_term_years, months / 12, months / 12)
        months != null -> pluralStringResource(R.plurals.deposit_term_months, months, months)
        else -> ChronoUnit.DAYS.between(start, end).toInt().let { pluralStringResource(R.plurals.deposit_term_days, it, it) }
    }
}

@Composable internal fun monthsLabel(months: Int): String =
    if (months % 12 == 0) pluralStringResource(R.plurals.deposit_term_years, months / 12, months / 12)
    else pluralStringResource(R.plurals.deposit_term_months, months, months)

@Composable internal fun termTitle(deposit: TermDeposit): String = stringResource(R.string.deposit_term_title,
    termLength(LocalDate.ofEpochDay(deposit.start_epoch_day), LocalDate.ofEpochDay(deposit.end_epoch_day)))

internal fun statusTone(deposit: TermDeposit, today: Long): TagTone =
    if (!deposit.closed && today >= deposit.end_epoch_day) TagTone.ATTENTION else TagTone.NEUTRAL

@Composable internal fun DepositStatusTag(deposit: TermDeposit, today: Long) {
    StatusTag(deposit_status(deposit, today), statusTone(deposit, today))
}

internal fun minorText(minor: Long, currency: Currency) = DecimalRules.format_display(minor, currency.fraction_digits)

/** The amount large and its currency small after it, as the account rows show a balance. */
@Composable internal fun DepositAmount(minor: Long, currency: Currency, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge) {
    val text = buildAnnotatedString {
        append(minorText(minor, currency))
        append(" ")
        withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            append(currency.code)
        }
    }
    Text(text, modifier, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1,
        autoSize = TextAutoSize.StepBased(10.sp, style.fontSize, .5.sp))
}

/** Interest earns, so it takes the gain color. */
@Composable internal fun InterestText(minor: Long, currency: Currency, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium, withCode: Boolean = false) {
    Text("+" + minorText(minor, currency) + if (withCode) " " + currency.code else "", modifier,
        style = style.copy(fontFeatureSettings = "tnum"), color = LocalGainLossPalette.current.gain, maxLines = 1)
}

/** How far along the term is, in words: days left, days to go before it starts, or how long it has waited. */
@Composable internal fun termProgressText(deposit: TermDeposit, today: Long): String {
    val closedAt = deposit.closedAtMs
    return when {
        deposit.closed && closedAt != null -> stringResource(R.string.deposit_settled_on,
            Instant.ofEpochMilli(closedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
        deposit.closed -> deposit_status(deposit, today)
        today < deposit.start_epoch_day -> (deposit.start_epoch_day - today).toInt()
            .let { pluralStringResource(R.plurals.deposit_starts_in, it, it) }
        today == deposit.end_epoch_day -> stringResource(R.string.deposit_matures_today)
        today > deposit.end_epoch_day -> (today - deposit.end_epoch_day).toInt()
            .let { pluralStringResource(R.plurals.deposit_matured_days, it, it) }
        else -> (deposit.end_epoch_day - today).toInt().let { pluralStringResource(R.plurals.deposit_days_left, it, it) }
    }
}

internal fun progressPercent(deposit: TermDeposit, today: Long): Int =
    if (deposit.closed) 100 else DecimalRules.progress(deposit.start_epoch_day, deposit.end_epoch_day, today)
        .multiply(BigDecimal(100)).toInt()

/** The term as a bar, with its dates on the left and what is left of it on the right. */
@Composable internal fun TermProgress(deposit: TermDeposit, today: Long, modifier: Modifier = Modifier) {
    val percent = progressPercent(deposit, today)
    val small = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth(),
            // A matured deposit's bar takes its tag's color, so the two read as one state.
            color = if (statusTone(deposit, today) == TagTone.ATTENTION) LocalTagPalette.current.attention.content
                else MaterialTheme.colorScheme.primary, drawStopIndicator = {})
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically) {
            Text(LocalDate.ofEpochDay(deposit.start_epoch_day).toString() + " → " +
                LocalDate.ofEpochDay(deposit.end_epoch_day).toString(), style = small, color = muted)
            Text(termProgressText(deposit, today) + " · " + percent + "%", style = small, color = muted)
        }
    }
}
