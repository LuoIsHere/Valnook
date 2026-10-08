package dev.valnook.designsystem

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal fun creditProgress(used: Double, limit: Double): Float =
    if (limit <= 0.0) { if (used > 0.0) 1f else 0f } else (used / limit).toFloat().coerceIn(0f, 1f)

@Composable fun CreditLimitProgress(used: Double, limit: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { creditProgress(used, limit) }, modifier = modifier.fillMaxWidth(),
        color = if (used > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        drawStopIndicator = {})
}
