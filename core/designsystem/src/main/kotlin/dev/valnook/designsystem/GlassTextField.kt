package dev.valnook.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * The app's text input ([ValnookTextField]) for pages that used the frosted one: a light fill with its label inside
 * and no outline. A test tag on [modifier] sits above the input node, as it always has.
 */
@Composable
fun GlassTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, isError: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null) =
    ValnookTextField(value, onValueChange, modifier, label = label, enabled = enabled, readOnly = readOnly,
        keyboardType = keyboardType, isError = isError, trailing = trailingIcon, tag = null)
