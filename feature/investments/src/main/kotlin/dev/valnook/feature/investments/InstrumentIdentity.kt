package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.Instrument

@Composable
internal fun InstrumentCodeLine(symbol: String, currency: String, modifier: Modifier = Modifier,
    symbolModifier: Modifier = Modifier, currencyModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (symbol.isNotBlank()) {
            Text(symbol, Modifier.weight(1f, fill = false).then(symbolModifier),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(currency, currencyModifier, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun InstrumentIdentity(instrument: Instrument) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(instrument.name, Modifier.weight(1f, fill = false).alignByBaseline().testTag("instrument-name-${instrument.id}"),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(instrument.typeName, Modifier.widthIn(max = 120.dp).alignByBaseline().testTag("instrument-type-${instrument.id}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        InstrumentCodeLine(instrument.symbol, instrument.currency.code,
            symbolModifier = Modifier.testTag("instrument-code-${instrument.id}"),
            currencyModifier = Modifier.testTag("instrument-currency-${instrument.id}"))
    }
}
