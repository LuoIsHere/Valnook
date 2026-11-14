package dev.valnook.feature.bills

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.domain.model.BillRateSource
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A rate as people read it: six significant digits at most. The bill keeps the full one. */
fun shortRate(rate: BigDecimal): String =
    rate.round(MathContext(SIGNIFICANT, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()

/** Where a rate came from, as the detail page names it; null for one currency. */
@Composable
fun rateSourceLabel(source: BillRateSource): String? = when (source) {
    BillRateSource.SAME -> null
    BillRateSource.AUTO -> stringResource(R.string.bill_rate_source_auto)
    BillRateSource.MANUAL -> stringResource(R.string.bill_rate_source_manual)
    BillRateSource.ONE_TIME -> stringResource(R.string.bill_rate_source_one_time)
    BillRateSource.DERIVED -> stringResource(R.string.bill_rate_source_derived)
}

private const val SIGNIFICANT = 6
