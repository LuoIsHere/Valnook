package dev.valnook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding

/**
 * The repository's THIRD_PARTY_NOTICES word for word, in English in every language. It runs to hundreds of lines, so
 * each paragraph is its own lazy item: section titles stand out, lists keep their lines, and prose wrapped for an
 * 80-column editor is joined and left to wrap at the screen's width.
 */
@Composable
fun ThirdPartyNoticesScreen() {
    val resources = LocalContext.current.resources
    val blocks = remember(resources) {
        resources.openRawResource(R.raw.third_party_notices).bufferedReader(Charsets.UTF_8).use { it.readText() }
            .let(::noticeBlocks)
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().testTag("third-party-page"), contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            itemsIndexed(blocks) { _, block ->
                when (block) {
                    is NoticeBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface)
                    is NoticeBlock.Lines -> Text(block.text, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is NoticeBlock.Prose -> Text(block.text, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

internal sealed interface NoticeBlock {
    val text: String
    data class Heading(override val text: String) : NoticeBlock
    data class Lines(override val text: String) : NoticeBlock
    data class Prose(override val text: String) : NoticeBlock
}

private val RULE = Regex("=+")
private val SOURCE = Regex("--- (.+) ---")

/**
 * Paragraphs of the notices file: the first one and any set between "====" rules are titles, "--- source ---" lines
 * name a library's NOTICE file, paragraphs whose lines (all but the last) fill the 80 columns are prose, and the rest
 * (library lists, "Used in:" fields) keep their line breaks.
 */
internal fun noticeBlocks(text: String): List<NoticeBlock> = text.replace("\r\n", "\n").trim()
    .split(Regex("\n\\s*\n")).mapIndexedNotNull { index, paragraph ->
        val all = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lines = all.filterNot { RULE.matches(it) }
        val source = lines.singleOrNull()?.let { SOURCE.matchEntire(it) }
        when {
            lines.isEmpty() -> null
            index == 0 && lines.size == 1 -> NoticeBlock.Heading(lines.single())
            all.size != lines.size -> NoticeBlock.Heading(lines.joinToString(" "))
            source != null -> NoticeBlock.Heading(source.groupValues[1])
            lines.size > 1 && lines.dropLast(1).all { it.length >= 55 } -> NoticeBlock.Prose(lines.joinToString(" "))
            else -> NoticeBlock.Lines(lines.joinToString("\n"))
        }
    }
