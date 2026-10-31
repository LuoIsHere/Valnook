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

/**
 * Shared financial form; navigation, draft storage and saving belong to the caller. The base currency and the rates
 * each sit in their own card under a section title. [readOnly] keeps the base selectable but shows the rates as a
 * plain list (automatic mode); [notes] adds a small line under a currency's rate.
 */
@Composable
fun FxRateEditor(base: Currency?, rows: List<FxRateDraft>, busy: Boolean,
    onBase: (Currency) -> Unit, onAdd: () -> Unit,
    onUpdate: (Int, Currency?, String?) -> Unit, onRemove: (Int) -> Unit,
    readOnly: Boolean = false, notes: Map<String, String> = emptyMap(), showDefaultHint: Boolean = true,
    emptyText: String? = null) {
    val editable = !busy && !readOnly
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionTitle(stringResource(R.string.settings_base_currency))
        GlassCard {
            Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Box(Modifier.testTag("fx-base")) {
                    CurrencyChoice(base?.code.orEmpty(), { onBase(Currency.of(it)) }, !busy,
                        Currency.supported.map { it.code to it.name }, glass = true)
                }
                Text(if (base == null) stringResource(R.string.settings_no_base) else
                    stringResource(R.string.settings_fx_direction, base.code), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (base != null && (rows.isNotEmpty() || !readOnly || emptyText != null)) {
            SectionTitle(stringResource(R.string.settings_exchange_rates), Modifier.padding(top = Space.md))
            GlassCard {
                if (rows.isEmpty() && readOnly && emptyText != null) Text(emptyText, Modifier.padding(Space.cardInset),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                    if (readOnly) RateLine(index, row, notes[row.sourceCurrency.code])
                    else RateInput(index, row, rows, base, editable, notes[row.sourceCurrency.code], onUpdate, onRemove)
                }
                if (!readOnly) {
                    if (rows.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                    Box(Modifier.padding(Space.cardInset)) {
                        ActionButton(onAdd, Modifier.testTag("fx-add"), enabled = editable) {
                            Text(stringResource(R.string.settings_add_rate))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (!readOnly) Text(stringResource(R.string.settings_rate_precision), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showDefaultHint) Text(stringResource(R.string.settings_fx_default), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = Space.xs), style = MaterialTheme.typography.titleMedium)
}

/** Automatic mode: the stored current rate as text, with its data date when it differs. */
@Composable
private fun RateLine(index: Int, row: FxRateDraft, note: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.cardInset, vertical = Space.md)
        .testTag("fx-rate-$index"), verticalAlignment = Alignment.CenterVertically) {
        Text(row.sourceCurrency.code, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.rateInput, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fx-note-${row.sourceCurrency.code}"))
            }
        }
    }
}

@Composable
private fun RateInput(index: Int, row: FxRateDraft, rows: List<FxRateDraft>, base: Currency, editable: Boolean, note: String?,
    onUpdate: (Int, Currency?, String?) -> Unit, onRemove: (Int) -> Unit) {
    Column(Modifier.padding(start = Space.cardInset, end = Space.xs, top = Space.md, bottom = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(0.38f)) {
                CurrencyChoice(row.sourceCurrency.code, { onUpdate(index, Currency.of(it), null) }, editable,
                    Currency.supported.map { it.code to it.name },
                    rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() + base.code,
                    glass = true, field = { open ->
                        ActionButton(open, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = editable) {
                            Text(row.sourceCurrency.code, Modifier.weight(1f), maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge)
                            ExpansionChevron(false)
                        }
                    })
            }
            Box(Modifier.weight(0.62f)) {
                GlassTextField(row.rateInput, { onUpdate(index, null, it) },
                    stringResource(R.string.settings_rate), Modifier.testTag("fx-rate-$index"),
                    enabled = editable, keyboardType = KeyboardType.Decimal)
            }
            IconButton({ onRemove(index) }, enabled = editable,
                modifier = Modifier.size(48.dp).testTag("remove-rate-${row.sourceCurrency.code}")) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.settings_remove_rate))
            }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fx-note-${row.sourceCurrency.code}"))
        }
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
