package dev.valnook.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Standard text editing, with a quiet frosted container and a native blinking cursor. */
@Composable
fun GlassTextField(value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text, isError: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val border = when {
        isError -> colors.error
        focused -> colors.outline.copy(alpha = 0.65f)
        else -> colors.outlineVariant.copy(alpha = if (enabled) 0.45f else 0.2f)
    }
    GlassSurface(modifier.border(1.dp, border, shape), shape = shape) {
        TextField(value, onValueChange, Modifier.fillMaxWidth().heightIn(min = 56.dp),
            label = { Text(label) }, singleLine = true, enabled = enabled, readOnly = readOnly,
            isError = isError, shape = shape, interactionSource = interaction,
            textStyle = MaterialTheme.typography.bodyLarge, trailingIcon = trailingIcon,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent,
                cursorColor = colors.onSurface))
    }
}
