package dev.valnook.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import java.math.BigDecimal

/** Shared financial form; navigation, draft storage and saving belong to the caller. */
@Composable
fun FxRateEditor(base: Currency?, rows: List<FxRateDraft>, busy: Boolean,
    onBase: (Currency) -> Unit, onAdd: () -> Unit,
    onUpdate: (Int, Currency?, String?) -> Unit, onRemove: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(stringResource(R.string.settings_base_currency), style = MaterialTheme.typography.titleLarge)
        Box(Modifier.testTag("fx-base")) {
            CurrencyChoice(base?.code.orEmpty(), { onBase(Currency.of(it)) }, !busy,
                Currency.supported.map { it.code to it.name }, glass = true)
        }
        Text(if (base == null) stringResource(R.string.settings_no_base) else
            stringResource(R.string.settings_fx_direction, base.code), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.settings_rate_precision), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.42f)) {
                    CurrencyChoice(row.sourceCurrency.code, { onUpdate(index, Currency.of(it), null) }, !busy,
                        Currency.supported.map { it.code to it.name },
                        rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() +
                            listOfNotNull(base?.code), glass = true, field = { open ->
                                ActionButton(open, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy) {
                                    Text(row.sourceCurrency.code, Modifier.weight(1f), maxLines = 1,
                                        style = MaterialTheme.typography.bodyLarge)
                                    ExpansionChevron(false)
                                }
                            })
                }
                Box(Modifier.weight(0.58f)) {
                    GlassTextField(row.rateInput, { onUpdate(index, null, it) },
                        stringResource(R.string.settings_rate), Modifier.testTag("fx-rate-$index"),
                        enabled = !busy, keyboardType = KeyboardType.Decimal)
                }
                IconButton({ onRemove(index) }, enabled = !busy,
                    modifier = Modifier.size(48.dp).testTag("remove-rate-${row.sourceCurrency.code}")) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.settings_remove_rate))
                }
            }
            HorizontalDivider(Modifier.padding(top = Space.xs))
        }
        ActionButton(onAdd, Modifier.testTag("fx-add"), enabled = !busy && base != null) {
            Text(stringResource(R.string.settings_add_rate))
        }
        Text(stringResource(R.string.settings_fx_default), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun validateFxRates(base: Currency, rows: List<FxRateDraft>): List<FxRate> {
    if (rows.map { it.sourceCurrency }.distinct().size != rows.size) throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
    return rows.map { row ->
        if (row.sourceCurrency == base) throw DomainException(ErrorCode.CURRENCY)
        if (!Regex("[0-9]+(?:\\.[0-9]+)?").matches(row.rateInput) || row.rateInput.length > 64) {
            throw DomainException(ErrorCode.FORMAT)
        }
        val value = BigDecimal(row.rateInput)
        if (value.signum() <= 0) throw DomainException(ErrorCode.POSITIVE)
        if (value.stripTrailingZeros().scale() > 12) throw DomainException(ErrorCode.PRECISION)
        if (value.precision() > 40) throw DomainException(ErrorCode.OVERFLOW)
        FxRate(row.sourceCurrency, base, value.stripTrailingZeros())
    }
}
