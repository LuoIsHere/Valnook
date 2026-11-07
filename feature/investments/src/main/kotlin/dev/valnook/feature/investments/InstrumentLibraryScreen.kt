package dev.valnook.feature.investments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.InstrumentAssets
import java.math.BigDecimal

/** What the title bar needs: adding an instrument waits for an asset class to put it in. */
data class InstrumentLibraryToolbarState(val canCreate: Boolean)

/** Asset classes, then adding, in the title bar, as the accounts page has its groups and adding. */
@Composable
fun InstrumentLibraryActions(state: InstrumentLibraryToolbarState?, onTypes: () -> Unit, onCreate: () -> Unit) {
    val typesLabel = stringResource(R.string.instrument_manage_types)
    val addLabel = stringResource(R.string.instrument_add)
    val canCreate = state?.canCreate == true
    IconButton(onTypes, Modifier.testTag("instrument-library-types").semantics { contentDescription = typesLabel }) {
        GroupsIcon()
    }
    IconButton(onCreate, Modifier.testTag("instrument-library-add").semantics { contentDescription = addLabel },
        enabled = canCreate) { AddIcon(enabled = canCreate) }
}

@Composable
fun InstrumentLibrary(vm: InstrumentLibraryViewModel, onOpen: (Long) -> Unit, onTypes: () -> Unit,
    onToolbar: (InstrumentLibraryToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(onToolbar) { onDispose { onToolbar(null) } }
    val current = state as? InstrumentLibraryState.Ready
    SideEffect { onToolbar(current?.let { InstrumentLibraryToolbarState(it.hasTypes) }) }
    if (current == null) {
        Text(stringResource(if (state == InstrumentLibraryState.Failed) R.string.instrument_library_failed
            else R.string.instrument_library_loading))
        return
    }
    InstrumentLibraryContent(current, onOpen, onTypes)
}

/** A search, the asset classes as capsules, then every instrument as a row of one card. */
@Composable
fun InstrumentLibraryContent(state: InstrumentLibraryState.Ready, onOpen: (Long) -> Unit, onTypes: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    // A type deleted while selected falls back to all types.
    val selectedType = typeFilter?.takeIf { id -> state.types.any { it.id == id } }
    val matches = state.instruments.filter {
        (selectedType == null || it.instrument.typeId == selectedType) &&
            (it.instrument.name.contains(query, true) || it.instrument.symbol.contains(query, true))
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("instrument-library"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "search") { SearchBox(stringResource(R.string.instrument_search), query) { query = it } }
        if (state.hasTypes) item(key = "types") {
            val counts = state.instruments.groupingBy { it.instrument.typeId }.eachCount()
            TypeCapsules(listOf(null to (stringResource(R.string.instrument_filter_all) to state.instruments.size)) +
                state.types.map { it.id to (it.name to (counts[it.id] ?: 0)) }, selectedType) { typeFilter = it }
        }
        if (!state.hasTypes) item(key = "no-types") {
            GlassCard(Modifier.testTag("instrument-library-no-types")) {
                Text(stringResource(R.string.instrument_create_type_first), Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onTypes).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.instrument_create_type), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    RowChevron()
                }
            }
        }
        // Without a class there can be no instrument: the card above already says what to do.
        if (matches.isEmpty()) { if (state.hasTypes) item(key = "empty") { EmptyState(stringResource(when {
            query.isNotBlank() -> R.string.instrument_no_match
            selectedType != null -> R.string.instrument_type_no_match
            else -> R.string.instrument_empty
        })) } }
        else {
            item(key = "card") {
                GlassCard(Modifier.testTag("instrument-library-card")) {
                    matches.forEachIndexed { index, summary ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                        LibraryRow(summary, summary.instrument.id in state.soldOut) { onOpen(summary.instrument.id) }
                    }
                }
            }
            item(key = "count") {
                Text(pluralStringResource(R.plurals.instrument_count, matches.size, matches.size),
                    Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** A search on a light fill, with a clear button once something is typed. */
@Composable private fun SearchBox(placeholder: String, value: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val style = MaterialTheme.typography.bodyLarge
    val minimum = (48f + 24f * (LocalDensity.current.fontScale.coerceAtLeast(1f) - 1f)).dp
    BasicTextField(value, onChange, Modifier.fillMaxWidth().testTag("instrument-library-search"), singleLine = true,
        textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = minimum).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer)
                .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) {
                    val clear = stringResource(R.string.instrument_search_clear)
                    IconButton({ onChange("") }, Modifier.size(40.dp).semantics { contentDescription = clear }) {
                        FaIcon(FaIcons.xmark, null, Modifier.size(16.dp), colors.onSurfaceVariant)
                    }
                } else Spacer(Modifier.width(10.dp))
            }
        })
}

/** All, then each asset class with how many instruments it has; slides sideways when they do not fit. */
@Composable private fun TypeCapsules(options: List<Pair<Long?, Pair<String, Int>>>, selected: Long?, onSelect: (Long?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = options.indexOfFirst { it.first == selected }
        if (index > 0) state.scrollToItem(index)
    }
    LazyRow(Modifier.fillMaxWidth().testTag("instrument-type-filter"), state = state,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options, key = { it.first ?: -1L }) { (id, label) ->
            val on = id == selected
            Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(percent = 50))
                .background(if (on) colors.secondaryContainer else colors.surfaceContainer)
                .selectable(on, role = Role.Tab) { onSelect(id) }
                .testTag(if (id == null) "instrument-type-all" else "instrument-type-option-$id")
                .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val content = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant
                Text(label.first, style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 1)
                Text(label.second.toString(), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = content.copy(alpha = .7f), maxLines = 1)
            }
        }
    }
}

/** The whole name over its symbol, currency and class; the current price beside it; then what it has made. */
@Composable private fun LibraryRow(summary: InstrumentAssets, soldOut: Boolean, onOpen: () -> Unit) {
    val instrument = summary.instrument
    val currency = instrument.currency
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.testTag("library-row-${instrument.id}").fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            NameAndTag(instrument.name,
                listOf(instrument.symbol, currency.code, instrument.typeName).filter { it.isNotBlank() }.joinToString(" · "),
                if (soldOut) ({ StatusTag(stringResource(R.string.instrument_not_held), TagTone.NEUTRAL,
                    Modifier.testTag("library-not-held-${instrument.id}")) }) else null,
                instrument.id, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                val style = MaterialTheme.typography.titleMedium
                Text(buildAnnotatedString {
                    append(BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString())
                    append(" ")
                    withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = muted)) { append(currency.code) }
                }, Modifier.testTag("library-price-${instrument.id}"), style = style.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1, textAlign = TextAlign.End)
                Text(stringResource(R.string.instrument_per_unit), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfitText(R.string.investment_unrealized_value, signed(summary.floating, currency, withCode = true),
                displayedSign(summary.floating, currency.fraction_digits), Modifier.weight(1f), TextAlign.Start)
            ProfitText(R.string.investment_realized_value, signed(summary.realized, currency, withCode = true),
                displayedSign(summary.realized, currency.fraction_digits), Modifier.weight(1f), TextAlign.End)
        }
    }
}

@Composable private fun ProfitText(label: Int, value: String, sign: Int, modifier: Modifier, align: TextAlign) {
    val text = stringResource(label, value)
    val color = profitColor(sign)
    Text(buildAnnotatedString {
        append(text)
        val start = text.indexOf(value)
        if (start >= 0) addStyle(SpanStyle(color = color), start, start + value.length)
    }, modifier, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
}

/**
 * The name on up to two lines, cut with "…" past that, and its symbol line under it. The tag follows the name
 * when both fit on one line; otherwise it starts the second line, so it never wraps on its own.
 */
@Composable private fun NameAndTag(name: String, meta: String, tag: (@Composable () -> Unit)?, id: Long, modifier: Modifier) {
    Layout(content = {
        Text(name, Modifier.testTag("library-name-$id"), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(meta, Modifier.testTag("library-meta-$id"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        tag?.invoke()
    }, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val lineGap = 2.dp.roundToPx()
        val loose = Constraints(maxWidth = width)
        val tagPlaceable = measurables.getOrNull(2)?.measure(loose)
        val tagSpace = tagPlaceable?.let { it.width + gap } ?: 0
        val nameFits = tagPlaceable == null || !constraints.hasBoundedWidth ||
            measurables[0].maxIntrinsicWidth(Constraints.Infinity) + tagSpace <= width
        val inline = tagPlaceable != null && nameFits
        val namePlaceable = measurables[0].measure(Constraints(maxWidth = if (inline) width - tagSpace else width))
        val metaPlaceable = measurables[1].measure(Constraints(maxWidth =
            if (tagPlaceable != null && !inline) (width - tagSpace).coerceAtLeast(0) else width))
        val firstLine = if (inline) maxOf(namePlaceable.height, tagPlaceable!!.height) else namePlaceable.height
        val secondLine = if (tagPlaceable != null && !inline) maxOf(metaPlaceable.height, tagPlaceable.height) else metaPlaceable.height
        val layoutWidth = if (constraints.hasBoundedWidth) width
            else maxOf(namePlaceable.width + (if (inline) tagSpace else 0), metaPlaceable.width + (if (inline) 0 else tagSpace))
        layout(layoutWidth, firstLine + lineGap + secondLine) {
            namePlaceable.place(0, (firstLine - namePlaceable.height) / 2)
            val second = firstLine + lineGap
            when {
                tagPlaceable == null -> metaPlaceable.place(0, second)
                inline -> {
                    tagPlaceable.place(namePlaceable.width + gap, (firstLine - tagPlaceable.height) / 2)
                    metaPlaceable.place(0, second)
                }
                else -> {
                    tagPlaceable.place(0, second + (secondLine - tagPlaceable.height) / 2)
                    metaPlaceable.place(tagSpace, second + (secondLine - metaPlaceable.height) / 2)
                }
            }
        }
    }
}
