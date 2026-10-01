package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun AccountProfitRow(realized: String, floating: String, realizedSign: Int, floatingSign: Int,
    modifier: Modifier = Modifier, realizedModifier: Modifier = Modifier, floatingModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(realized, Modifier.weight(1f).alignByBaseline().then(realizedModifier),
            style = MaterialTheme.typography.bodySmall, color = profitColor(realizedSign))
        Text(floating, Modifier.weight(1f).alignByBaseline().then(floatingModifier),
            style = MaterialTheme.typography.bodySmall, color = profitColor(floatingSign), textAlign = TextAlign.End)
    }
}
