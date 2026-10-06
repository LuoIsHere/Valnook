package dev.valnook.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified

/** Measure the original size so shrinking cannot repeatedly toggle the wrapping decision. */
@Composable
fun AccountName(name: String, style: TextStyle, modifier: Modifier = Modifier) {
    val baseStyle = LocalTextStyle.current.merge(style)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val wraps = measurer.measure(name, style = baseStyle, maxLines = 2,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = constraints.maxWidth)).lineCount > 1
        val displayStyle = if (wraps) baseStyle.copy(
            fontSize = if (baseStyle.fontSize.isSpecified) baseStyle.fontSize * .9f else baseStyle.fontSize,
            lineHeight = if (baseStyle.lineHeight.isSpecified) baseStyle.lineHeight * .9f else baseStyle.lineHeight
        ) else baseStyle
        Text(name, style = displayStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
