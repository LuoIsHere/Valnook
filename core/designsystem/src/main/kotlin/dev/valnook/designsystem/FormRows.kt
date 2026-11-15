package dev.valnook.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import kotlinx.coroutines.delay

private val FieldShape = RoundedCornerShape(12.dp)

/** A small label over one card of rows, as on the account detail page. */
@Composable
fun FormSection(label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, Modifier.padding(start = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard(content = content)
    }
}

/** The line between rows of a card; [inset] leaves room on the left, 0 runs it edge to edge. */
@Composable
fun FormDivider(modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    HorizontalDivider(modifier.padding(start = inset), color = MaterialTheme.colorScheme.surfaceContainer)
}

/** Keeps the caller's text and this field's cursor apart, so ± can move the cursor with the sign. */
@Composable
private fun rememberCursor(value: String): MutableState<TextFieldValue> = remember { mutableStateOf(TextFieldValue(value)) }

@Composable
private fun AttentionEffect(requestAttention: Boolean, requester: BringIntoViewRequester) {
    LaunchedEffect(requestAttention) { if (requestAttention) { delay(100); requester.bringIntoView() } }
}

/** One line of a grouped card: the label on the left and the text typed straight in on the right. */
@Composable
fun FormTextRow(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, placeholder: String = "", error: String? = null, requestAttention: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { BringIntoViewRequester() }
    AttentionEffect(requestAttention, requester)
    val message = error?.let { errorText(it) }
    var cursor by rememberCursor(value)
    val style = MaterialTheme.typography.bodyLarge
    Column(modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        BasicTextField(cursor.copy(text = value), { cursor = it; if (it.text != value) onChange(it.text) },
            Modifier.fillMaxWidth().testTag("input-$label")
                .semantics(mergeDescendants = true) { if (message != null) error(message) },
            enabled = enabled, singleLine = true,
            textStyle = style.copy(color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f)),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            decorationBox = { inner ->
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, Modifier.widthIn(min = 48.dp, max = 120.dp), style = style,
                        color = if (message != null) colors.error else colors.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty())
                            Text(placeholder, style = style, color = colors.onSurfaceVariant.copy(alpha = .6f))
                        inner()
                    }
                }
            })
        if (message != null) Text(message, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

/**
 * A setting that is on or off: [title], an optional [summary] under it, and anything else in [extra]; the whole line
 * toggles it. A test tag on [modifier] reads as the switch itself. [contentPadding] fits it to a card or a column.
 */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    summary: String? = null, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            extra?.invoke(this)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * One option of a single choice: the radio first, then [leading] (an icon), [title] and an optional [summary]. The
 * whole line selects it. Put the options of one choice in a column with `Modifier.selectableGroup()`.
 */
@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    summary: String? = null, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    leading: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** [SwitchRow] with its title alone, tagged "switch-" and the title. */
@Composable
fun FormSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) =
    SwitchRow(label, checked, onChange, modifier.testTag("switch-$label"), enabled = enabled, contentPadding = contentPadding)

@Composable
private fun filledMinimumHeight(label: Boolean) =
    ((if (label) 56f else 48f) + 24f * (LocalDensity.current.fontScale.coerceAtLeast(1f) - 1f)).dp

/** The light fill every input shares: a small label over the content when there is one, no outline. */
@Composable
private fun FilledFrame(label: String?, error: Boolean, enabled: Boolean, trailing: (@Composable () -> Unit)?,
    shape: Shape = FieldShape, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = filledMinimumHeight(label != null)).clip(shape)
        .background(colors.surfaceContainer)
        .then(if (error) Modifier.border(1.dp, colors.error, shape) else Modifier)
        .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = when {
                error -> colors.error
                enabled -> colors.onSurfaceVariant
                else -> colors.onSurfaceVariant.copy(alpha = .6f)
            })
            content()
        }
        trailing?.invoke()
    }
}

/**
 * The text input of the app: a light fill with no outline, an optional small [label] in its top-left corner, and
 * [placeholder] while empty. One line unless [maxLines] allows more; [trailing] sits at its right end. [error] and
 * [supporting] are messages shown under it; [isError] marks it red without a message. The input node carries [tag].
 */
@Composable
fun ValnookTextField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
    placeholder: String = "", enabled: Boolean = true, error: String? = null, supporting: String? = null,
    numeric: Boolean = false, signed: Boolean = false, minLines: Int = 1, maxLines: Int = 1,
    password: Boolean = false, monospace: Boolean = false, keyboardType: KeyboardType? = null,
    autoCorrect: Boolean = true, shape: Shape = FieldShape, trailing: (@Composable () -> Unit)? = null,
    tag: String? = label?.let { "input-$it" }, requestAttention: Boolean = false, readOnly: Boolean = false,
    isError: Boolean = error != null, prefix: String? = null) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { BringIntoViewRequester() }
    AttentionEffect(requestAttention, requester)
    var cursor by rememberCursor(value)
    val shown = cursor.copy(text = value)
    val toggle = if (numeric && signed) stringResource(R.string.toggle_sign) else ""
    val singleLine = maxLines <= 1 && minLines <= 1
    val base = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodyLarge
    val style = base.copy(fontFeatureSettings = if (numeric) "tnum" else null)
    val type = keyboardType ?: when {
        password -> KeyboardType.Password
        numeric -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }
    val end: (@Composable () -> Unit)? = if (numeric && signed) ({
        // 40dp keeps this field as tall as a plain one beside it.
        IconButton({ cursor = toggleSign(shown); onChange(cursor.text) }, Modifier.size(40.dp), enabled = enabled) {
            FaIcon(FaIcons.plusMinus, toggle, Modifier.size(18.dp))
        }
    }) else trailing
    val note = error ?: supporting
    Box(modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
        BasicTextField(shown, { cursor = it; if (it.text != value) onChange(it.text) },
            Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .semantics(mergeDescendants = true) { if (error != null) error(error) },
            enabled = enabled, readOnly = readOnly, singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxOf(minLines, maxLines),
            textStyle = style.copy(color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .6f)),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = autoCorrect && !password,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            // The message under the fill belongs to the field, as in a Material text field: one node reads both.
            decorationBox = { inner ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledFrame(label, isError, enabled, end, shape) {
                        Row {
                            if (prefix != null) Text(prefix, style = style, color = colors.onSurfaceVariant)
                            Box {
                                if (value.isEmpty() && placeholder.isNotEmpty())
                                    Text(placeholder, style = style, color = colors.onSurfaceVariant.copy(alpha = .6f))
                                inner()
                            }
                        }
                    }
                    if (note != null) Text(note, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (error != null) colors.error else colors.onSurfaceVariant)
                }
            })
    }
}

/** A text input on a light fill, with its label inside, for the editing panels. [error] is an error code. */
@Composable
fun FilledField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    numeric: Boolean = false, signed: Boolean = false, enabled: Boolean = true, placeholder: String = "",
    error: String? = null, requestAttention: Boolean = false) =
    ValnookTextField(value, onChange, modifier, label, placeholder, enabled, error?.let { errorText(it) },
        numeric = numeric, signed = signed, requestAttention = requestAttention)

/** Looks like [FilledField] but opens a choice. Reads as "label: value", as [SelectorField] does. */
@Composable
fun FilledChoiceField(label: String, value: String, onSelect: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box(modifier.fillMaxWidth().testTag("input-$label").clip(FieldShape)
        .clickable(enabled = enabled, role = Role.Button) { focus.clearFocus(); keyboard?.hide(); onSelect() }
        .clearAndSetSemantics {
            contentDescription = "$label: $value"
            role = Role.Button
            if (enabled) onClick { onSelect(); true } else disabled()
        }) {
        FilledFrame(label, false, enabled, if (enabled) ({
            FaIcon(FaIcons.chevronDown, null, Modifier.padding(end = 8.dp).size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }) else null) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
    }
}

/**
 * The filled look of [FilledChoiceField] with the semantics a read-only text field has: the label and value stay text
 * (found by text, read in order), and a layer over them takes the tap and reads as "label: value". [SelectorField] and
 * the pickers built on it use it.
 */
@Composable
internal fun FilledSelector(label: String, value: String, onSelect: () -> Unit, enabled: Boolean) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxWidth().testTag("input-$label")) {
        FilledFrame(label, false, enabled, {
            FaIcon(FaIcons.chevronDown, null, Modifier.padding(end = 8.dp).size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        Box(Modifier.matchParentSize().clip(FieldShape)
            .clickable(enabled = enabled, role = Role.Button) { focus.clearFocus(); keyboard?.hide(); onSelect() }
            .semantics { contentDescription = "$label: $value" })
    }
}

/** [FilledChoiceField] with its options in a menu below it. */
@Composable
fun FilledDropdownField(label: String, selected: String, options: List<Pair<String, String>>,
    onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        FilledChoiceField(label, options.firstOrNull { it.first == selected }?.second.orEmpty(), { expanded = true },
            enabled = enabled)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}
