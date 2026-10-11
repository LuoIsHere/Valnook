package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.TrendChart
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@Composable internal fun CashTrendChart(points:List<CashTrendPoint>,account:CashAccount,
    modifier:Modifier=Modifier,compact:Boolean=false) {
    // Display the signed ledger balance, including credit overpayments; never clamp it to zero.
    // 展示真实账面余额（含信用账户溢缴款），不能把正余额或负余额截断为零。
    val values=remember(points) { points.map { it.balanceMinor } }
    val normalized=remember(values) {
        val low=values.filterNotNull().minOrNull()?:BigInteger.ZERO
        val high=values.filterNotNull().maxOrNull()?:BigInteger.ZERO
        val range=(high-low).takeIf { it.signum()!=0 }
        values.map { value -> value?.let { if(range==null) .5f else
            .08f+.84f*(1f-BigDecimal(it-low).divide(BigDecimal(range),10,RoundingMode.HALF_UP).toFloat()) } }
    }
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale,account.currency) { NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits=account.currency.fraction_digits;maximumFractionDigits=account.currency.fraction_digits
        if(this is java.text.DecimalFormat) decimalFormatSymbols=decimalFormatSymbols.apply { groupingSeparator=' ';decimalSeparator='.' }
    } }
    fun amount(value:BigInteger?)=value?.let { number.format(BigDecimal(it,account.currency.fraction_digits))+" "+account.currency.code }?:"—"
    var selected by remember(account.id,points) { mutableStateOf<Int?>(null) }
    val selectedPoint=selected?.let(points::getOrNull)
    val displayed=if(selected==null)BigInteger.valueOf(account.balance_minor)else selectedPoint?.balanceMinor
    val date=selectedPoint?.date?:points.lastOrNull()?.date?:LocalDate.now()
    val dateText=date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val readout=dateText+" · "+amount(displayed)
    val description=stringResource(R.string.cash_current_balance)+"; "+points.joinToString("; ") { "${it.date}: ${amount(it.balanceMinor)}" }
    val labels=remember(points,locale) { points.mapIndexedNotNull { index,point ->
        if(index==0 || point.date.dayOfMonth==1) index to point.date.month.getDisplayName(TextStyle.SHORT,locale) else null
    } }
    Column(modifier) {
        if(!compact) {
            Text(amount(displayed),style=MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings="tnum"),
                modifier=Modifier.testTag("cash-trend-value"))
            Text(dateText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(top=4.dp,bottom=12.dp).testTag("cash-trend-date"))
        }
        TrendChart(normalized,labels,selected,{selected=it},curvePath={ group ->
            Path().apply {
                moveTo(group.first().x,group.first().y)
                MonotoneCurve.segments(group.map { CurvePoint(it.x,it.y) }).forEach { s ->
                    cubicTo(s.control1.x,s.control1.y,s.control2.x,s.control2.y,s.end.x,s.end.y)
                }
            }
        },description=description,readout=readout,compact=compact,animationKey="cash-${account.id}",
            modifier=Modifier.height(if(compact)48.dp else 176.dp).testTag(if(compact)"cash-sparkline-${account.id}"else "cash-trend-chart"))
    }
}
