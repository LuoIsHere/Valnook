package dev.valnook.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import java.math.BigDecimal

private val RateFieldShape = RoundedCornerShape(10.dp)

/**
 * Shared financial form; navigation, draft storage and saving belong to the caller. The base currency and the rates
 * each sit in a grouped card, as on the account edit page: a rate row removes with ⊖, changes its currency by tapping
 * the code and takes the rate straight in its field. [readOnly] keeps the base selectable but shows the rates as
 * plain text (automatic mode); [notes] adds a small line under a currency's rate.
 */
@Composable
fun FxRateEditor(base: Currency?, rows: List<FxRateDraft>, busy: Boolean,
    onBase: (Currency) -> Unit, onAdd: () -> Unit,
    onUpdate: (Int, Currency?, String?) -> Unit, onRemove: (Int) -> Unit,
    readOnly: Boolean = false, notes: Map<String, String> = emptyMap(), showDefaultHint: Boolean = true,
    emptyText: String? = null) {
    val editable = !busy && !readOnly
    val language = interfaceLanguage()
    val options = remember(language) { Currency.options(language) }
    val names = remember(options) { options.toMap() }
    val currencyWidth = currencyColumnWidth(rows.map { it.sourceCurrency.code }, names, arrow = editable)
    Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The row already says what it is, so the card needs no title of its own.
            FormSection(null) {
                CurrencyChoice(base?.code.orEmpty(), { onBase(Currency.of(it)) }, !busy, options, glass = true,
                    field = { open -> BaseRow(base, names[base?.code], !busy, open) })
            }
            if (base == null) Hint(stringResource(R.string.settings_no_base))
        }
        if (base != null && (rows.isNotEmpty() || !readOnly || emptyText != null)) {
            FormSection(stringResource(R.string.settings_fx_rates_section, base.code)) {
                if (rows.isEmpty() && readOnly && emptyText != null) Text(emptyText, Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { index, row ->
                    if (index > 0) FormDivider()
                    val note = notes[row.sourceCurrency.code]
                    if (readOnly) RateLine(index, row, names[row.sourceCurrency.code], note)
                    else RateRow(index, row, names[row.sourceCurrency.code], note, editable, currencyWidth, options,
                        rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() + base.code,
                        onUpdate, onRemove)
                }
                if (!readOnly) {
                    if (rows.isNotEmpty()) FormDivider()
                    AddRow(onAdd, editable)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (!readOnly) Hint(stringResource(R.string.settings_rate_precision))
            if (showDefaultHint) Hint(stringResource(R.string.settings_fx_default))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The code, then the name in what room is left, then an optional arrow. A name cut down to a few letters says
 * nothing, so below that it is left out rather than shown as an ellipsis.
 */
@Composable
private fun CurrencyLabel(code: String, name: String?, arrow: Boolean, modifier: Modifier = Modifier) {
    Layout(content = {
        Text(code, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        Text(name.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (arrow) ExpansionChevron(false, Modifier.size(12.dp)) else Box(Modifier)
    }, modifier) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val codePlaced = measurables[0].measure(loose)
        val arrowPlaced = measurables[2].measure(loose)
        val fixed = codePlaced.width + if (arrowPlaced.width > 0) gap + arrowPlaced.width else 0
        val room = constraints.maxWidth - fixed - gap
        val full = if (name.isNullOrEmpty()) 0 else measurables[1].maxIntrinsicWidth(constraints.maxHeight)
        val namePlaced = if (full > 0 && room >= minOf(full, 40.dp.roundToPx()))
            measurables[1].measure(loose.copy(maxWidth = room)) else null
        val width = (fixed + (namePlaced?.let { gap + it.width } ?: 0)).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = maxOf(codePlaced.height, namePlaced?.height ?: 0, arrowPlaced.height).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            var x = 0
            codePlaced.place(x, (height - codePlaced.height) / 2)
            x += codePlaced.width + gap
            namePlaced?.let { it.place(x, (height - it.height) / 2); x += it.width + gap }
            arrowPlaced.place(x, (height - arrowPlaced.height) / 2)
        }
    }
}

/** The whole line opens the currency picker. */
@Composable
private fun BaseRow(base: Currency?, name: String?, enabled: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("fx-base")
        .clickable(enabled, role = Role.Button, onClick = open)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The label is measured first, so the currency's name gives way before the label wraps.
        Text(stringResource(R.string.settings_base_currency), style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (base == null) Text(stringResource(R.string.settings_not_set), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else CurrencyLabel(base.code, name, arrow = false)
        }
        RowChevron()
    }
}

/** Automatic mode: the stored current rate as text, with its data date when it differs. */
@Composable
private fun RateLine(index: Int, row: FxRateDraft, name: String?, note: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("fx-rate-$index"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CurrencyLabel(row.sourceCurrency.code, name, arrow = false, Modifier.weight(1f))
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
private fun RateRow(index: Int, row: FxRateDraft, name: String?, note: String?, editable: Boolean, currencyWidth: Dp,
    options: List<Pair<String, String>>, excluded: Set<String>,
    onUpdate: (Int, Currency?, String?) -> Unit, onRemove: (Int) -> Unit) {
    val code = row.sourceCurrency.code
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        // Every row's currency gets the width the widest one needs, up to 45% of the line, so the fields line up;
        // the field gets the rest, since twelve decimal places are the longer part, more so with a large font.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val currencyMax = minOf(currencyWidth, (maxWidth - 48.dp) * .45f)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                SignButton(plus = false, enabled = editable, description = stringResource(R.string.settings_remove_rate, code),
                    modifier = Modifier.testTag("remove-rate-$code")) { onRemove(index) }
                Box(Modifier.width(currencyMax)) {
                    CurrencyChoice(code, { onUpdate(index, Currency.of(it), null) }, editable, options, excluded, glass = true,
                        field = { open ->
                            Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(editable, onClickLabel = stringResource(R.string.settings_change_currency),
                                    role = Role.Button, onClick = open)
                                .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CurrencyLabel(code, name, arrow = editable)
                            }
                        })
                }
                Box(Modifier.weight(1f).padding(start = 8.dp).testTag("fx-rate-$index")) {
                    RateField(row.rateInput, { onUpdate(index, null, it) }, editable, stringResource(R.string.settings_rate_of, code))
                }
            }
        }
        note?.let {
            Text(it, Modifier.padding(start = 48.dp, bottom = 8.dp).testTag("fx-note-$code"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the widest of [codes] needs as a [CurrencyLabel] with its padding, in the current font size. */
@Composable
private fun currencyColumnWidth(codes: List<String>, names: Map<String, String>, arrow: Boolean): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val codeStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val nameStyle = MaterialTheme.typography.bodyMedium
    return remember(codes, names, arrow, density, codeStyle, nameStyle) {
        with(density) {
            codes.maxOfOrNull { code ->
                val codePx = measurer.measure(code, codeStyle, maxLines = 1).size.width
                val namePx = names[code]?.let { measurer.measure(it, nameStyle, maxLines = 1).size.width } ?: 0
                codePx.toDp() + (if (namePx > 0) 6.dp + namePx.toDp() else 0.dp) + (if (arrow) 18.dp else 0.dp)
            }?.plus(8.dp + 1.dp) ?: 0.dp
        }
    }
}

/** A decimal typed straight into the row, right-aligned on a light fill so it reads as editable. */
@Composable
private fun RateField(value: String, onChange: (String) -> Unit, enabled: Boolean, description: String) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(value, onChange,
        Modifier.fillMaxWidth().semantics { contentDescription = description },
        enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End,
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RateFieldShape).background(colors.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.CenterEnd) { inner() }
        })
}

/** The last line of the rates card; the whole line is the button. */
@Composable
private fun AddRow(onAdd: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("fx-add")
        .clickable(enabled, role = Role.Button, onClick = onAdd).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { SignIcon(plus = true, enabled = enabled) }
        Text(stringResource(R.string.settings_add_rate), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
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
