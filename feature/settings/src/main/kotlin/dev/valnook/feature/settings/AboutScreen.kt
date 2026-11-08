package dev.valnook.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding

@Composable
fun AboutScreen(versionName: String, onPrivacyPolicy: () -> Unit, onLicense: () -> Unit = {}, internalBuildId: String = "",
    onThirdParty: () -> Unit = {}) {
    val uriHandler = LocalUriHandler.current
    val repositoryUrl = stringResource(R.string.settings_repository_url)
    LazyColumn(Modifier.fillMaxSize().testTag("about-page"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("Valnook", style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.settings_footer_version, versionName),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (internalBuildId.isNotBlank()) Text(stringResource(R.string.settings_footer_internal, internalBuildId),
                    Modifier.testTag("about-internal-build"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_copyright),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_license), Modifier.testTag("about-license-name"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            GlassCard {
                SettingEntry(stringResource(R.string.settings_privacy_policy),
                    stringResource(R.string.settings_privacy_policy_summary), onPrivacyPolicy,
                    Modifier.testTag("about-privacy-policy"))
                HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                SettingEntry(stringResource(R.string.settings_license), stringResource(R.string.settings_footer_license),
                    onLicense, Modifier.testTag("about-license"))
                HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                SettingEntry(stringResource(R.string.settings_third_party),
                    stringResource(R.string.settings_third_party_summary), onThirdParty,
                    Modifier.testTag("about-third-party"))
                HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                SettingEntry(stringResource(R.string.settings_repository), repositoryUrl,
                    // A device without a browser cannot open it; nothing happens rather than a crash.
                    { runCatching { uriHandler.openUri(repositoryUrl) } }, Modifier.testTag("about-repository"),
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_github), contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color.White else Color.Black)
                    })
            }
        }
        item {
            Text(stringResource(R.string.settings_github_attribution),
                modifier = Modifier.padding(Space.cardInset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
