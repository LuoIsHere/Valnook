package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle

@Composable
internal fun AccountProfitRow(realized: String, floating: String, realizedSign: Int, floatingSign: Int,
    modifier: Modifier = Modifier, realizedModifier: Modifier = Modifier, floatingModifier: Modifier = Modifier) {
    val realizedText = stringResource(R.string.investment_realized_value, realized)
    val floatingText = stringResource(R.string.investment_unrealized_value, floating)
    val realizedColor = profitColor(realizedSign)
    val floatingColor = profitColor(floatingSign)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(buildAnnotatedString {
            append(realizedText)
            val start = realizedText.indexOf(realized)
            if (start >= 0) addStyle(SpanStyle(color = realizedColor), start, start + realized.length)
        }, Modifier.weight(1f).alignByBaseline().then(realizedModifier),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(buildAnnotatedString {
            append(floatingText)
            val start = floatingText.indexOf(floating)
            if (start >= 0) addStyle(SpanStyle(color = floatingColor), start, start + floating.length)
        }, Modifier.weight(1f).alignByBaseline().then(floatingModifier),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}
