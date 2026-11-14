package dev.valnook.feature.ai

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** A secondary action on the AI pages: a button on a light background, so it reads as one next to plain text. */
@Composable
internal fun AiTonalButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilledTonalButton(onClick, modifier, enabled = enabled, contentPadding = Padding) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The main action of a draft or of the input box, in the primary colour. */
@Composable
internal fun AiPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier, enabled = enabled, contentPadding = Padding) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
