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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

@Composable
fun PrivacyPolicyScreen() {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val policy = remember(resources, locale) {
        resources.openRawResource(R.raw.privacy_policy).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    val styledPolicy = remember(policy) {
        // Keep these exact bilingual sentences synchronized with WalletPrivateDialogs and both raw policies.
        // 此处仅给相同免责声明加粗；修改任一语言时，同时更新风险提示及四份中英文政策文件。
        buildAnnotatedString {
            append(policy)
            listOf("Valnook为按照MIT协议发行的开源软件，不对您的财产损失负任何责任",
                "Valnook is open-source software distributed under the MIT License and accepts no liability for any financial loss you incur.").forEach { notice ->
                val start=policy.indexOf(notice)
                if(start>=0)addStyle(SpanStyle(fontWeight=FontWeight.Bold),start,start+notice.length)
            }
        }
    }
    SelectionContainer {
        Column(Modifier.fillMaxSize().testTag("privacy-policy-page")
            .verticalScroll(rememberScrollState()).padding(pageContentPadding())) {
            Text(styledPolicy, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("privacy-policy-text"))
        }
    }
}
