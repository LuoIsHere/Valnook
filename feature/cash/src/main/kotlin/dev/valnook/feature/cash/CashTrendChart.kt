package dev.valnook.feature.cash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.roundToInt

@Composable internal fun CashTrendChart(points:List<CashTrendPoint>,account:CashAccount,
    modifier:Modifier=Modifier,compact:Boolean=false) {
    val values=remember(points,account.type) { points.map { point -> point.balanceMinor?.let {
        if(account.type==BalanceAccountType.CREDIT) it.negate().max(BigInteger.ZERO) else it
    } } }
    val low=values.filterNotNull().minOrNull()?:BigInteger.ZERO
    val high=values.filterNotNull().maxOrNull()?:BigInteger.ZERO
    val range=(high-low).takeIf { it.signum()!=0 }
    val ys=values.map { value -> value?.let { if(range==null) .5f else
        1f-(BigDecimal(it-low).divide(BigDecimal(range),8,java.math.RoundingMode.HALF_UP)).toFloat() } }
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    val grid=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)
    val label=stringResource(if(account.type==BalanceAccountType.CREDIT)R.string.cash_amount_owed else R.string.cash_current_balance)
    val locale=LocalConfiguration.current.locales[0]
    fun amount(value:BigInteger?)=value?.let { BigDecimal(it,account.currency.fraction_digits).toPlainString()+" "+account.currency.code }?:"—"
    val description=points.mapIndexed { i,p -> "${p.month}: ${amount(values[i])}" }.joinToString("; ")
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    Column(modifier.semantics { contentDescription="$label; $description" }) {
        if(!compact) {
            // Reserve one small line so selection never moves the chart or the ledger.
            Text(selected?.let { "${points[it].month.month.getDisplayName(java.time.format.TextStyle.SHORT,locale)} · ${amount(values[it])}" }?:"",
                style=MaterialTheme.typography.labelMedium,color=ink,modifier=Modifier.heightIn(min=22.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(if(compact)48.dp else 132.dp).then(if(compact)Modifier else
            Modifier.pointerInput(points) { detectTapGestures { position ->
                if(points.isNotEmpty())selected=((position.x/size.width)*(points.size-1)).roundToInt().coerceIn(points.indices)
            } })) {
            val inset=4.dp.toPx(); val width=(size.width-2*inset).coerceAtLeast(1f)
            val height=(size.height-2*inset).coerceAtLeast(1f)
            if(!compact)repeat(3) { i -> val y=inset+height*i/2f;drawLine(grid,Offset(inset,y),Offset(size.width-inset,y),1.dp.toPx()) }
            fun xy(index:Int,y:Float)=CurvePoint(inset+width*index/(ys.size-1).coerceAtLeast(1),inset+height*y)
            val segments=mutableListOf<List<CurvePoint>>(); var run=mutableListOf<CurvePoint>()
            ys.forEachIndexed { i,y -> if(y==null){if(run.isNotEmpty())segments.add(run);run=mutableListOf()}
                else run.add(xy(i,y)) };if(run.isNotEmpty())segments.add(run)
            segments.forEach { group ->
                val path=Path().apply { moveTo(group.first().x,group.first().y)
                    MonotoneCurve.segments(group).forEach { s -> cubicTo(s.control1.x,s.control1.y,s.control2.x,s.control2.y,s.end.x,s.end.y) } }
                if(group.size>1) {
                    if(!compact) {
                        val fill=Path().apply { addPath(path);lineTo(group.last().x,size.height);lineTo(group.first().x,size.height);close() }
                        drawPath(fill,Brush.verticalGradient(listOf(ink.copy(alpha=.10f),Color.Transparent)))
                    }
                    drawPath(path,ink,style=Stroke(1.8.dp.toPx(),cap=StrokeCap.Round))
                }
                val last=group.last();drawCircle(ink,if(compact)2.dp.toPx()else 2.8.dp.toPx(),Offset(last.x,last.y))
            }
            selected?.let { i -> ys.getOrNull(i)?.let { y -> val p=xy(i,y)
                drawLine(grid,Offset(p.x,inset),Offset(p.x,size.height-inset),1.dp.toPx())
                drawCircle(ink,4.dp.toPx(),Offset(p.x,p.y))
            } }
        }
        if(!compact)Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            points.forEach { Text(it.month.monthValue.toString(),style=MaterialTheme.typography.labelSmall,color=ink) }
        }
    }
}
