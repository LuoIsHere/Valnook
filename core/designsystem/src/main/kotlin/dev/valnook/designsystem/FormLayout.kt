package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R

/** Visual form shell; business input, errors and commands belong to the calling feature. */
@Composable
fun FormLayout(title: String, busy: Boolean, enabled: Boolean, onSave: () -> Unit,
    saveLabel: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .verticalScroll(rememberScrollState()).padding(pageContentPadding()),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        if (title != LocalPageTitle.current) Text(title, style = MaterialTheme.typography.titleLarge)
        content()
        Button(onClick = onSave, enabled = !busy && enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(saveLabel ?: stringResource(R.string.save))
        }
    }
}
