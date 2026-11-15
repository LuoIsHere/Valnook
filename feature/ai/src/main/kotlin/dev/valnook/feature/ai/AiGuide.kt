package dev.valnook.feature.ai

import dev.valnook.designsystem.SecondaryButton
import dev.valnook.designsystem.PrimaryButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.ChevronIcon
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The example in the quick start: DeepSeek's values and pages as its documentation gave them on [CHECKED]. Only data:
 * when DeepSeek changes a name or an address, this is the one place to change. Filling it in is the same as typing it.
 */
internal object AiGuideExample {
    val CHECKED: LocalDate = LocalDate.of(2026, 11, 15)
    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-flash"
    const val EXTRA_PARAMS = ""
    const val THINKING_OFF_PARAMS = """{"thinking": {"type": "disabled"}}"""
    const val THINKING_ON_PARAMS = """{"thinking": {"type": "enabled"}}"""
    const val PLATFORM = "https://platform.deepseek.com/"
    const val API_KEYS = "https://platform.deepseek.com/api_keys"
    private const val DOCS = "https://api-docs.deepseek.com/"

    fun docs(chinese: Boolean) = DOCS + if (chinese) "zh-cn/" else ""
    fun pricing(chinese: Boolean) = docs(chinese) + "quick_start/pricing"
    fun thinking(chinese: Boolean) = docs(chinese) + "guides/thinking_mode"

    /** The form with the example in place of the address, model and parameters; the key is left as it is. */
    fun fill(form: AiConnectionForm) = form.copy(baseUrl = BASE_URL, model = MODEL, extraParams = EXTRA_PARAMS,
        thinkingOffParams = THINKING_OFF_PARAMS, thinkingOnParams = THINKING_ON_PARAMS)

    /** Whether filling it in would replace something already typed: an address, model or parameters of another value. */
    fun replaces(form: AiConnectionForm): Boolean {
        fun differs(typed: String, example: String) = typed.isNotBlank() && typed.trim() != example
        return differs(form.baseUrl, BASE_URL) || differs(form.model, MODEL) || differs(form.extraParams, EXTRA_PARAMS) ||
            differs(form.thinkingOffParams, THINKING_OFF_PARAMS) || differs(form.thinkingOnParams, THINKING_ON_PARAMS)
    }
}

private const val PAGES = 8

/**
 * Settings → AI → Quick start: one card per step, the notice first and the quick setup last. [onFill] asks the AI
 * settings to take the example; nothing is saved from here.
 */
@Composable
fun AiGuideScreen(onFill: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    Column(Modifier.fillMaxSize().padding(pageContentPadding()).testTag("ai-guide")) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = Space.md) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("ai-guide-page-$page")) {
                GlassCard {
                    Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        when (page) {
                            0 -> NoticePage()
                            1 -> PreparePage()
                            2 -> KeyPage()
                            3 -> UrlPage(chinese)
                            4 -> ModelPage(chinese)
                            5 -> ParamsPage(chinese)
                            6 -> CheckPage()
                            else -> FillPage(onFill)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.ai_guide_previous), { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                Modifier.testTag("ai-guide-previous"), enabled = pager.currentPage > 0)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Dots(pager.currentPage)
                Text(stringResource(R.string.ai_guide_step, pager.currentPage + 1, PAGES), Modifier.testTag("ai-guide-step"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PrimaryButton(stringResource(R.string.ai_guide_next), { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.testTag("ai-guide-next"), enabled = pager.currentPage < PAGES - 1)
        }
    }
}

@Composable
private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(PAGES) { page ->
            Box(Modifier.size(6.dp).clip(CircleShape).background(
                if (page == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Small(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Lines that read as a list: numbered steps, or points. */
@Composable
private fun Points(items: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        items.forEachIndexed { index, item ->
            Row {
                Text(if (numbered) "${index + 1}." else "•", Modifier.width(20.dp), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** An example value, selectable so it can be copied. */
@Composable
private fun Value(label: String?, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))
        .padding(horizontal = Space.md, vertical = Space.sm).testTag(tag), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (value.isEmpty()) Text(stringResource(R.string.ai_guide_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

/** A page on another site, opened in the browser; its address shows under the name so it can be checked first. */
@Composable
private fun Link(label: String, url: String, tag: String) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { uri.openUri(url) }
        .testTag(tag).padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChevronIcon(right = true)
    }
}

@Composable
private fun NoticePage() {
    val date = AiGuideExample.CHECKED.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .withLocale(LocalConfiguration.current.locales[0]))
    Title(stringResource(R.string.ai_guide_notice_title))
    Points(listOf(stringResource(R.string.ai_guide_notice_service), stringResource(R.string.ai_guide_notice_example),
        stringResource(R.string.ai_guide_notice_checked, date), stringResource(R.string.ai_guide_notice_links),
        stringResource(R.string.ai_guide_notice_costs), stringResource(R.string.ai_guide_notice_key)))
}

@Composable
private fun PreparePage() {
    Title(stringResource(R.string.ai_guide_prepare_title))
    Body(stringResource(R.string.ai_guide_prepare_body))
    Points(listOf(stringResource(R.string.ai_guide_prepare_url), stringResource(R.string.ai_guide_prepare_key),
        stringResource(R.string.ai_guide_prepare_model)))
    Small(stringResource(R.string.ai_guide_prepare_more))
}

@Composable
private fun KeyPage() {
    Title(stringResource(R.string.ai_guide_key_title))
    Points(listOf(stringResource(R.string.ai_guide_key_step_1), stringResource(R.string.ai_guide_key_step_2),
        stringResource(R.string.ai_guide_key_step_3), stringResource(R.string.ai_guide_key_step_4)), numbered = true)
    Small(stringResource(R.string.ai_guide_key_note))
    Link(stringResource(R.string.ai_guide_link_platform), AiGuideExample.PLATFORM, "ai-guide-link-platform")
    Link(stringResource(R.string.ai_guide_link_api_keys), AiGuideExample.API_KEYS, "ai-guide-link-api-keys")
}

@Composable
private fun UrlPage(chinese: Boolean) {
    Title(stringResource(R.string.ai_guide_url_title))
    Body(stringResource(R.string.ai_guide_url_body))
    Value(null, AiGuideExample.BASE_URL, "ai-guide-value-url")
    Small(stringResource(R.string.ai_guide_url_note))
    Small(stringResource(R.string.ai_guide_url_cleartext))
    Link(stringResource(R.string.ai_guide_link_docs), AiGuideExample.docs(chinese), "ai-guide-link-docs")
}

@Composable
private fun ModelPage(chinese: Boolean) {
    Title(stringResource(R.string.ai_guide_model_title))
    Body(stringResource(R.string.ai_guide_model_body))
    Value(stringResource(R.string.ai_guide_model_example), AiGuideExample.MODEL, "ai-guide-value-model")
    Small(stringResource(R.string.ai_guide_model_note))
    Link(stringResource(R.string.ai_guide_link_pricing), AiGuideExample.pricing(chinese), "ai-guide-link-pricing")
}

@Composable
private fun ParamsPage(chinese: Boolean) {
    Title(stringResource(R.string.ai_guide_params_title))
    Body(stringResource(R.string.ai_guide_params_body))
    Body(stringResource(R.string.ai_guide_params_extra))
    Body(stringResource(R.string.ai_guide_params_off))
    Value(null, AiGuideExample.THINKING_OFF_PARAMS, "ai-guide-value-thinking-off")
    Body(stringResource(R.string.ai_guide_params_on))
    Value(null, AiGuideExample.THINKING_ON_PARAMS, "ai-guide-value-thinking-on")
    Small(stringResource(R.string.ai_guide_params_note))
    Link(stringResource(R.string.ai_guide_link_thinking), AiGuideExample.thinking(chinese), "ai-guide-link-thinking")
}

@Composable
private fun CheckPage() {
    Title(stringResource(R.string.ai_guide_check_title))
    Body(stringResource(R.string.ai_guide_check_body))
    Points(listOf(stringResource(R.string.ai_guide_check_required), stringResource(R.string.ai_guide_check_optional)))
    Small(stringResource(R.string.ai_guide_check_fail))
    Small(stringResource(R.string.ai_guide_check_enable))
}

@Composable
private fun FillPage(onFill: () -> Unit) {
    Title(stringResource(R.string.ai_guide_fill_title))
    Body(stringResource(R.string.ai_guide_fill_body))
    Value(stringResource(R.string.ai_base_url), AiGuideExample.BASE_URL, "ai-guide-fill-url")
    Value(stringResource(R.string.ai_model), AiGuideExample.MODEL, "ai-guide-fill-model")
    Value(stringResource(R.string.ai_extra_params), AiGuideExample.EXTRA_PARAMS, "ai-guide-fill-extra")
    Value(stringResource(R.string.ai_thinking_off_params), AiGuideExample.THINKING_OFF_PARAMS, "ai-guide-fill-thinking-off")
    Value(stringResource(R.string.ai_thinking_on_params), AiGuideExample.THINKING_ON_PARAMS, "ai-guide-fill-thinking-on")
    Small(stringResource(R.string.ai_guide_fill_after))
    PrimaryButton(stringResource(R.string.ai_guide_fill), onFill, Modifier.fillMaxWidth().testTag("ai-guide-fill"))
    Link(stringResource(R.string.ai_guide_link_platform), AiGuideExample.PLATFORM, "ai-guide-fill-platform")
}
