package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable fun MonthPager(month: YearMonth, onChange: (YearMonth) -> Unit) {
    val previous = stringResource(R.string.ledger_previous_month)
    val next = stringResource(R.string.ledger_next_month)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        MonthArrow(previous, false, { onChange(month.minusMonths(1)) }, month.year > 1)
        Text(month.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.getDefault())),
            Modifier.padding(horizontal = 16.dp).testTag("ledger-month"), style = MaterialTheme.typography.titleSmall)
        MonthArrow(next, true, { onChange(month.plusMonths(1)) }, month.year < 9999)
    }
}

@Composable private fun MonthArrow(label: String, right: Boolean, click: () -> Unit, enabled: Boolean) {
    val color = LocalContentColor.current
    IconButton(click, Modifier.size(36.dp).testTag(if (right) "month-next" else "month-previous")
        .semantics { contentDescription = label }, enabled = enabled) {
        Canvas(Modifier.size(16.dp)) {
            val x = if (right) .65f else .35f
            val other = 1f - x
            drawLine(color, Offset(size.width * other, size.height*.2f), Offset(size.width*x, size.height*.5f), 2.dp.toPx())
            drawLine(color, Offset(size.width*x, size.height*.5f), Offset(size.width * other, size.height*.8f), 2.dp.toPx())
        }
    }
}
