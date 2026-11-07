package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import java.math.BigDecimal

/** One instrument's realized profit in its own currency and, at today's rate, in the base currency. */
data class RealizedRow(val summary: InstrumentAssets, val held: Boolean, val inBase: BigDecimal?)

/**
 * Every traded instrument that has realized something, sold out or still held: largest first by its base value (its
 * own amount when there is no rate), and those that can't be worked out last.
 */
internal fun realizedRows(snapshot: AssetSnapshot, summaries: List<InstrumentAssets>): List<RealizedRow> {
    val rates = FxRateResolver(snapshot.settings, snapshot.fx)
    val positions = snapshot.positions.groupBy { it.instrumentId }
    return summaries.mapNotNull { summary ->
        val own = positions[summary.instrument.id] ?: return@mapNotNull null
        val currency = summary.instrument.currency
        val realized = summary.realized
        if (realized != null && displayedSign(realized, currency.fraction_digits) == 0) return@mapNotNull null
        RealizedRow(summary, own.any { it.holding_quantity_e8 > 0 },
            realized?.let { value -> rates.today(currency.code)?.let(value::multiply) })
    }.sortedWith(compareBy<RealizedRow> { it.summary.realized == null }
        .thenByDescending { (it.inBase ?: it.summary.realized)?.abs() }
        .thenBy { it.summary.instrument.name })
}

@Composable fun RealizedProfitScreen(vm: PortfolioViewModel, onInstrument: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PortfolioState.Loading -> PageLoading()
        PortfolioState.Failed -> PageFailure(stringResource(R.string.investment_load_failed), vm::reload)
        is PortfolioState.Ready -> {
            val rows = remember(current) { realizedRows(current.snapshot, current.instrumentSummaries) }
            RealizedProfitContent(current.overview.realized, current.snapshot.settings.baseCurrency, rows, onInstrument)
        }
    }
}

/** The total as on the investments page, then one card of instruments. */
@Composable fun RealizedProfitContent(total: ConvertedTotal, base: Currency?, rows: List<RealizedRow>,
    onInstrument: (Long) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize().testTag("realized-page"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item(key = "total") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.investment_total_realized), style = MaterialTheme.typography.bodySmall, color = muted)
                val style = MaterialTheme.typography.headlineMedium
                val sign = total.currency?.let { displayedSign(total.amount, it.fraction_digits) } ?: 0
                Text(converted(total), Modifier.testTag("realized-total"), maxLines = 1,
                    style = style.copy(fontFeatureSettings = "tnum"),
                    autoSize = TextAutoSize.StepBased(14.sp, style.fontSize, .5.sp),
                    color = if (total.complete) profitColor(sign) else muted)
                if (rows.isNotEmpty()) Text(pluralStringResource(R.plurals.realized_count, rows.size, rows.size),
                    style = MaterialTheme.typography.bodySmall, color = muted)
                IncompleteReason(total)
            }
        }
        if (rows.isEmpty()) item(key = "empty") { EmptyState(stringResource(R.string.realized_empty)) }
        else {
            item(key = "card") {
                GlassCard(Modifier.testTag("realized-card")) {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                        RealizedRowView(row, base) { onInstrument(row.summary.instrument.id) }
                    }
                }
            }
            item(key = "footer") {
                Text(stringResource(R.string.realized_footer), Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** The name and class as in the instrument library; its own amount on the right, and the base amount under it. */
@Composable private fun RealizedRowView(row: RealizedRow, base: Currency?, onOpen: () -> Unit) {
    val instrument = row.summary.instrument
    val currency = instrument.currency
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.testTag("realized-row-${instrument.id}").fillMaxWidth().clickable(onClick = onOpen).padding(12.dp)) {
    // The amounts take at most half the row and shrink to fit it, so large type never cuts them.
    val amountWidth = maxWidth * .5f
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        val state = stringResource(if (row.held) R.string.realized_held else R.string.realized_sold_out)
        NameAndTag(instrument.name, listOf(instrument.symbol, currency.code, state).filter { it.isNotBlank() }.joinToString(" · "),
            { StatusTag(typeLabel(instrument.typeName), TagTone.NEUTRAL) }, instrument.id, Modifier.weight(1f))
        Column(Modifier.widthIn(max = amountWidth), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val realized = row.summary.realized
            if (realized == null) Text(stringResource(R.string.realized_unknown),
                Modifier.testTag("realized-amount-${instrument.id}"), style = MaterialTheme.typography.bodyMedium, color = muted)
            else {
                val style = MaterialTheme.typography.titleMedium
                Text(signed(realized, currency, withCode = true), Modifier.testTag("realized-amount-${instrument.id}"),
                    style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1, textAlign = TextAlign.End,
                    autoSize = TextAutoSize.StepBased(10.sp, style.fontSize, .5.sp),
                    color = profitColor(displayedSign(realized, currency.fraction_digits)))
            }
            // Only a foreign amount with a rate is said again in the base currency.
            if (base != null && base != currency && row.inBase != null) Text(
                stringResource(R.string.realized_in_base, signed(row.inBase, base, withCode = true)),
                Modifier.testTag("realized-base-${instrument.id}"),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = muted, maxLines = 1,
                autoSize = TextAutoSize.StepBased(8.sp, MaterialTheme.typography.bodySmall.fontSize, .5.sp))
        }
    }
    }
}
