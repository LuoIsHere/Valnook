package dev.valnook.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.ai.AiService

/** Read-only: exactly what a bill request sends, tools included; the key is in a header and not shown. */
@Composable
fun AiPromptScreen(service: AiService) {
    val text by produceState<String?>(null, service) { value = runCatching { service.promptPreview() }.getOrDefault("") }
    LazyColumn(Modifier.fillMaxSize().testTag("ai-prompt"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            Text(stringResource(R.string.ai_prompt_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            GlassCard {
                val shown = text
                if (shown == null) CircularProgressIndicator(Modifier.padding(Space.cardInset))
                else SelectionContainer {
                    Text(shown, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Space.cardInset)
                        .testTag("ai-prompt-text"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
        item { AiDisclaimer() }
    }
}
