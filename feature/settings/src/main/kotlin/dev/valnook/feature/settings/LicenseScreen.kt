package dev.valnook.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.valnook.designsystem.pageContentPadding

/**
 * The repository's LICENSE word for word, in English in every language, laid out like the privacy policy. Its lines
 * are wrapped for an 80-column editor, so each paragraph is joined into one and left to wrap at the screen's width.
 */
@Composable
fun LicenseScreen() {
    val resources = LocalContext.current.resources
    val text = remember(resources) {
        resources.openRawResource(R.raw.license).bufferedReader(Charsets.UTF_8).use { it.readText() }.let(::reflowLicense)
    }
    SelectionContainer {
        Column(Modifier.fillMaxSize().testTag("license-page").verticalScroll(rememberScrollState())
            .padding(pageContentPadding())) {
            Text(text, Modifier.testTag("license-text"), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Joins the lines of each paragraph; paragraphs stay apart. */
internal fun reflowLicense(text: String): String = text.replace("\r\n", "\n").trim()
    .split(Regex("\n\\s*\n")).joinToString("\n\n") { paragraph -> paragraph.lines().joinToString(" ") { it.trim() } }
