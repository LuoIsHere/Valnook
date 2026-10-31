package dev.valnook.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.app.R
import dev.valnook.designsystem.LocalNoticePalette
import dev.valnook.designsystem.Space
import dev.valnook.domain.fx.FxSyncError
import dev.valnook.domain.fx.FxSyncNotice
import kotlinx.coroutines.delay

/**
 * Pink strip over any page after a background exchange rate update failed or left currencies without a rate.
 * Each notice shows once: it is consumed when it hides, after four seconds or a tap. Manual updates never raise one.
 */
@Composable
internal fun FxNoticeBanner(notice: FxSyncNotice?, onConsume: (String) -> Unit, modifier: Modifier = Modifier) {
    val current = notice ?: return
    val consume by rememberUpdatedState(onConsume)
    val accessibility = LocalAccessibilityManager.current
    var visible by remember(current.id) { mutableStateOf(true) }
    LaunchedEffect(current.id) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(4_000, containsIcons = false, containsText = true,
            containsControls = false) ?: 4_000L)
        visible = false
        consume(current.id)
    }
    if (!visible) return
    val palette = LocalNoticePalette.current
    Surface(onClick = { visible = false; consume(current.id) },
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.md).testTag("fx-notice-banner"),
        color = palette.container, contentColor = palette.onContainer, tonalElevation = 6.dp, shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
            Text(stringResource(if (current.error == null) R.string.fx_notice_missing_title else R.string.fx_notice_failed_title),
                style = MaterialTheme.typography.titleSmall)
            Text(fxNoticeReason(current), style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

@Composable
private fun fxNoticeReason(notice: FxSyncNotice): String {
    val day = notice.dataDate?.format(java.time.format.DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(LocalConfiguration.current.locales[0], "MMMd"),
        LocalConfiguration.current.locales[0]))
    val using = if (day == null) stringResource(R.string.fx_notice_using_none) else stringResource(R.string.fx_notice_using, day)
    val reason = when (notice.error) {
        null -> stringResource(R.string.fx_notice_missing, notice.missingCurrencies.joinToString(", "))
        FxSyncError.NETWORK, FxSyncError.TIMEOUT -> stringResource(R.string.fx_notice_network)
        FxSyncError.RATE_LIMITED, FxSyncError.SERVICE, FxSyncError.PARSE -> stringResource(R.string.fx_notice_service)
        FxSyncError.STORAGE -> stringResource(R.string.fx_notice_storage)
    }
    // Chinese sentences join without a space; English keeps one.
    return stringResource(R.string.fx_notice_sentences, reason, using)
}
