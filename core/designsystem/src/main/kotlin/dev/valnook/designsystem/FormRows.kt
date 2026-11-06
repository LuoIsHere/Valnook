package dev.valnook.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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

@Composable
fun FormDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceContainer)
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

/** A setting that is on or off; the whole line toggles it. */
@Composable
fun FormSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("switch-$label")
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun filledMinimumHeight() = (56f + 24f * (LocalDensity.current.fontScale.coerceAtLeast(1f) - 1f)).dp

@Composable
private fun FilledFrame(label: String, error: Boolean, enabled: Boolean, trailing: (@Composable () -> Unit)?,
    content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = filledMinimumHeight()).clip(FieldShape)
        .background(colors.surfaceContainer)
        .then(if (error) Modifier.border(1.dp, colors.error, FieldShape) else Modifier)
        .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = when {
                error -> colors.error
                enabled -> colors.onSurfaceVariant
                else -> colors.onSurfaceVariant.copy(alpha = .6f)
            })
            content()
        }
        trailing?.invoke()
    }
}

/** A text input on a light fill, with its label inside, for the editing panels. */
@Composable
fun FilledField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    numeric: Boolean = false, signed: Boolean = false, enabled: Boolean = true, placeholder: String = "",
    error: String? = null, requestAttention: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { BringIntoViewRequester() }
    AttentionEffect(requestAttention, requester)
    val message = error?.let { errorText(it) }
    var cursor by rememberCursor(value)
    val shown = cursor.copy(text = value)
    val toggle = if (numeric && signed) stringResource(R.string.toggle_sign) else ""
    val style = MaterialTheme.typography.bodyLarge
    Column(modifier.fillMaxWidth().bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicTextField(shown, { cursor = it; if (it.text != value) onChange(it.text) },
            Modifier.fillMaxWidth().testTag("input-$label")
                .semantics(mergeDescendants = true) { if (message != null) error(message) },
            enabled = enabled, singleLine = true,
            textStyle = style.copy(fontFeatureSettings = if (numeric) "tnum" else null,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .6f)),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            decorationBox = { inner ->
                FilledFrame(label, message != null, enabled, if (numeric && signed) ({
                    IconButton({ cursor = toggleSign(shown); onChange(cursor.text) }, enabled = enabled) {
                        FaIcon(FaIcons.plusMinus, toggle, Modifier.size(18.dp))
                    }
                }) else null) {
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty())
                            Text(placeholder, style = style, color = colors.onSurfaceVariant.copy(alpha = .6f))
                        inner()
                    }
                }
            })
        if (message != null) Text(message, Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
}

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
