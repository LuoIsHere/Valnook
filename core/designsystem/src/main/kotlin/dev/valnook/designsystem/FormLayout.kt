package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Visual form shell; business input, errors and commands belong to the calling feature. */
@Composable
fun FormLayout(title: String, busy: Boolean, enabled: Boolean, onSave: () -> Unit,
    saveLabel: String = "保存", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
        Button(onClick = onSave, enabled = !busy && enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(saveLabel)
        }
    }
}
