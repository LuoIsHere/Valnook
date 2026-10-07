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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import dev.valnook.designsystem.pageContentPadding

@Composable
fun PrivacyPolicyScreen() {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val policy = remember(resources, locale) {
        resources.openRawResource(R.raw.privacy_policy).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    SelectionContainer {
        Column(Modifier.fillMaxSize().testTag("privacy-policy-page")
            .verticalScroll(rememberScrollState()).padding(pageContentPadding())) {
            Text(policy, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("privacy-policy-text"))
        }
    }
}
