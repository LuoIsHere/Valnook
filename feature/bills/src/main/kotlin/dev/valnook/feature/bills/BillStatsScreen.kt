package dev.valnook.feature.bills

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.BillShare
import dev.valnook.domain.model.BillTotals
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The ring shows this many categories by name; the rest share one grey part. The list below names them all. */
private const val RING_PARTS = 7

/**
 * A month of expense ([expense] true) or income on the statistics page: the totals against the month before, the
 * parts by category, and the sums by day. [onCategory] opens a category's bills in a month when given; [onDay] the
 * bill list on a day.
 */
@Composable
fun BillStatsPage(vm: BillStatsViewModel, expense: Boolean, onCategory: ((Long?, java.time.YearMonth) -> Unit)? = null,
    onDay: ((LocalDate) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val side = if (expense) state.expense else state.income
    val kind = if (expense) "expense" else "income"
    LazyColumn(Modifier.fillMaxSize().testTag("bill-stats-$kind"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        item("summary") { SummaryCard(state, expense, vm::setMonth) }
        if (state.loaded) {
            item("categories") { CategoryCard(state, side, expense, onCategory) }
            item("days") { DailyCard(state, side, expense, onDay) }
        }
    }
}

@Composable private fun SummaryCard(state: BillStatsState, expense: Boolean, onMonth: (java.time.YearMonth) -> Unit) {
    val totals = state.totals
    val base = state.base?.code
    val colors = MaterialTheme.colorScheme
    val palette = LocalGainLossPalette.current
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(Space.cardInset).testTag("bill-stats-summary"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (expense) R.string.stats_total_expense else R.string.stats_total_income),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            val main = totals?.let { if (expense) it.expense else it.income }
            Text(main?.let { totalText(it, base, totals) } ?: "—", Modifier.testTag("bill-stats-total"),
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (totals != null) {
                val balance = totals.balance
                val other = if (expense) totals.income else totals.expense
                Text(colouredText(if (expense) R.string.bills_month_income_balance else R.string.stats_expense_balance,
                    totalText(other, base, totals) to Color.Unspecified,
                    totalText(balance, base, totals) to when (balance.signum()) {
                        1 -> palette.gain; -1 -> palette.loss; else -> Color.Unspecified }),
                    Modifier.testTag("bill-stats-other"), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                compareText(totals, state.previous, expense, base)?.let {
                    Text(it, Modifier.testTag("bill-stats-compare"), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                val savings = state.savings
                if (expense && savings != null && savings.value.signum() > 0) Text(
                    stringResource(R.string.stats_savings, totalText(savings.value, base, totals)),
                    Modifier.testTag("bill-stats-savings"), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                if (totals.partial || state.previous?.partial == true || savings?.partial == true)
                    Text(stringResource(R.string.bills_totals_partial), Modifier.testTag("bill-stats-partial"),
                        style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            MonthPager(state.month, onMonth, horizontalArrangement = Arrangement.Start,
                label = { m, l -> localMonthLabel(m, l) })
        }
    }
}

/** "12.00 CNY (8.3%) more than last month", the amount alone when last month had none, or null without both months. */
@Composable private fun compareText(now: BillTotals, before: BillTotals?, expense: Boolean, base: String?): String? {
    before ?: return null
    val current = if (expense) now.expense else now.income
    val last = if (expense) before.expense else before.income
    val change = current - last
    if (change.signum() == 0) return stringResource(R.string.stats_compare_same)
    val amount = totalText(change.abs(), base, if (now.converted || before.converted) now.copy(converted = true) else now)
    val more = change.signum() > 0
    if (last.signum() == 0) return stringResource(if (more) R.string.stats_compare_more_plain else R.string.stats_compare_less_plain, amount)
    val percent = change.abs().multiply(BigDecimal(100)).divide(last, 1, RoundingMode.HALF_UP).toPlainString() + "%"
    return stringResource(if (more) R.string.stats_compare_more else R.string.stats_compare_less, amount, percent)
}

@Composable private fun CategoryCard(state: BillStatsState, side: BillStatsSide, expense: Boolean,
    onCategory: ((Long?, java.time.YearMonth) -> Unit)?) {
    val totals = state.totals ?: return
    val base = state.base?.code
    val total = side.shares.fold(BigDecimal.ZERO) { sum, share -> sum + share.value }
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(vertical = Space.cardInset).testTag("bill-stats-categories"),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(stringResource(R.string.stats_by_category), Modifier.padding(horizontal = Space.cardInset),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (side.shares.isEmpty()) {
                Text(stringResource(if (expense) R.string.stats_no_expense else R.string.stats_no_income),
                    Modifier.fillMaxWidth().padding(Space.cardInset).testTag("bill-stats-empty"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val names = side.shares.associate { it.categoryId to shareName(it, state) }
            val colors = side.shares.associate { it.categoryId to shareColor(it, state) }
            val other = MaterialTheme.colorScheme.outline
            val slices = remember(side.shares, colors, other) {
                val shown = side.shares.take(RING_PARTS).map { share ->
                    RingSlice(share.categoryId ?: -1L, fraction(share.value, total), colors.getValue(share.categoryId))
                }
                val rest = side.shares.drop(RING_PARTS).fold(BigDecimal.ZERO) { sum, share -> sum + share.value }
                if (rest.signum() > 0) shown + RingSlice("other", fraction(rest, total), other) else shown
            }
            val description = stringResource(R.string.stats_ring_description, side.shares.take(RING_PARTS).joinToString("，") {
                names.getValue(it.categoryId) + " " + percentText(it.value, total)
            })
            RingChart(slices, description, Modifier.align(Alignment.CenterHorizontally).width(176.dp)
                .padding(vertical = Space.sm).testTag("bill-stats-ring"), thickness = 18.dp,
                animationKey = "bill-stats-ring-${if (expense) "expense" else "income"}") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(totalText(if (expense) totals.expense else totals.income, base, totals).removeSuffix(" ${base.orEmpty()}"),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                    Text(pluralStringResource(R.plurals.stats_bill_count, side.shares.sumOf { it.count },
                        side.shares.sumOf { it.count }), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val largest = side.shares.first().value
            side.shares.forEach { share ->
                CategoryRow(share, names.getValue(share.categoryId), colors.getValue(share.categoryId), state, total, largest,
                    base, totals, onCategory)
            }
        }
    }
}

@Composable private fun CategoryRow(share: BillShare, name: String, color: Color, state: BillStatsState, total: BigDecimal,
    largest: BigDecimal, base: String?, totals: BillTotals, onCategory: ((Long?, java.time.YearMonth) -> Unit)?) {
    val category = share.categoryId?.let(state.categories::get)
    val digits = state.base?.fraction_digits ?: 2
    Row(Modifier.fillMaxWidth()
        .then(if (onCategory != null) Modifier.clickable { onCategory(share.categoryId, state.month) } else Modifier)
        .padding(horizontal = Space.cardInset, vertical = Space.xs).testTag("bill-stats-category-${share.categoryId ?: "none"}")
        // Read as one line: name, amount, bills and share.
        .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        CategoryIcon(category?.iconKey ?: "circle-question", category?.colorKey ?: "grey", size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text((if (totals.converted) "≈ " else "") + DecimalRules.format_display(share.value.setScale(digits,
                    RoundingMode.HALF_UP), digits), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
            }
            // Lengths against the largest category, so the first bar is full and the rest compare with it.
            ShareBar(fraction(share.value, largest), color)
            Text(pluralStringResource(R.plurals.stats_bill_count, share.count, share.count) + " · " +
                percentText(share.value, total), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun ShareBar(fraction: Float, color: Color) {
    // Grows in with the ring the first time the page shows, then follows new values.
    val shown by androidx.compose.animation.core.animateFloatAsState(fraction, tween(450), label = "share-bar")
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
    }
}

@Composable private fun DailyCard(state: BillStatsState, side: BillStatsSide, expense: Boolean, onDay: ((LocalDate) -> Unit)?) {
    val totals = state.totals ?: return
    val base = state.base?.code
    val month = state.month
    val days = month.lengthOfMonth()
    // Days still to come in this month have no bar at all, rather than an empty one.
    val values = remember(side.days, month, state.today) {
        val largest = side.days.values.maxOrNull()?.takeIf { it.signum() > 0 }
        List(days) { index ->
            val date = month.atDay(index + 1)
            if (date.isAfter(state.today)) null
            else side.days[date]?.let { value -> largest?.let { fraction(value, it) } } ?: 0f
        }
    }
    var selected by rememberSaveable(month, expense) { mutableStateOf<Int?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val dayFormat = remember(locale) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale)
    }
    val counted = values.count { it != null }
    val sum = side.days.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val readout = selected?.let { index ->
        val date = month.atDay(index + 1)
        date.format(dayFormat) + " · " + totalText((side.days[date] ?: BigDecimal.ZERO)
            .setScale(state.base?.fraction_digits ?: 2, RoundingMode.HALF_UP), base, totals)
    } ?: stringResource(R.string.stats_daily_average, totalText(if (counted == 0) BigDecimal.ZERO else
        sum.divide(BigDecimal(counted), state.base?.fraction_digits ?: 2, RoundingMode.HALF_UP), base, totals))
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(Space.cardInset).testTag("bill-stats-days"),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(stringResource(if (expense) R.string.stats_daily_expense else R.string.stats_daily_income),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(readout, Modifier.weight(1f), label = "bill-stats-readout",
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }) { text ->
                    Text(text, Modifier.testTag("bill-stats-readout"), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val day = selected?.let { month.atDay(it + 1) }
                if (day != null && onDay != null) TextButton({ onDay(day) }, Modifier.testTag("bill-stats-open-day")) {
                    Text(stringResource(R.string.stats_open_day))
                }
            }
            val labels = remember(days) { listOf(1, 8, 15, 22, days).distinct().map { it - 1 to it.toString() } }
            DailyBars(values, labels, selected, { selected = it }, LocalTrendPalette.current.line,
                stringResource(if (expense) R.string.stats_daily_expense else R.string.stats_daily_income) + "，" + readout,
                Modifier.fillMaxWidth().height(140.dp).testTag("bill-stats-bars"),
                animationKey = "bill-stats-bars-${if (expense) "expense" else "income"}")
        }
    }
}

@Composable private fun shareName(share: BillShare, state: BillStatsState): String =
    share.categoryId?.let(state.categories::get)?.let { categoryName(it) } ?: stringResource(R.string.bill_category_missing)

@Composable private fun shareColor(share: BillShare, state: BillStatsState): Color =
    billColor(share.categoryId?.let(state.categories::get)?.colorKey ?: "grey")

private fun fraction(part: BigDecimal, whole: BigDecimal): Float =
    if (whole.signum() <= 0) 0f else part.divide(whole, 6, RoundingMode.HALF_UP).toFloat()

/** "34.5%", and "<0.1%" for a sliver that would round to nothing. */
internal fun percentText(part: BigDecimal, whole: BigDecimal): String {
    if (whole.signum() <= 0) return "0%"
    val percent = part.multiply(BigDecimal(100)).divide(whole, 1, RoundingMode.HALF_UP)
    return if (percent.signum() == 0 && part.signum() > 0) "<0.1%" else percent.toPlainString() + "%"
}
