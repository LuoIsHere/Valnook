package dev.valnook.feature.ai

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillCategoryKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings → AI: connection, checks, defaults and what is personal. [onDirtyChange] lets the root ask before leaving. */
@Composable
fun AiSettingsScreen(vm: AiSettingsViewModel, onPrompt: () -> Unit, onDirtyChange: (Boolean) -> Unit = {}) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.dirty) { onDirtyChange(ui.dirty) }
    // Leaving drops the drafts, so nothing is unsaved any more.
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
    if (!ui.loaded) return
    LazyColumn(Modifier.fillMaxSize().testTag("ai-settings"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { EnableCard(ui, vm) }
        item { ConnectionCard(ui, vm) }
        item { ChecksCard(ui, vm) }
        if (ui.settings.enabled && ui.canEnable) item { FeaturesCard(ui, vm) }
        item { DefaultsCard(ui, vm) }
        item { PersonalCard(ui, vm) }
        if (ui.usage != null) item { DataCard(ui, vm) }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPrompt).testTag("ai-prompt-entry")
                    .padding(horizontal = Space.cardInset, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_prompt_view), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ai_prompt_view_summary), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChevronIcon(right = true)
                }
            }
        }
        item { AiDisclaimer() }
    }
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
        .padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

/**
 * The master switch: off by default, and only on once the required checks passed. Turning it on shows the notice,
 * worded like the privacy policy, every time until "Don't show again", as the rates and card details notices do.
 */
@Composable
private fun EnableCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val context = LocalContext.current
    var notice by remember { mutableStateOf(false) }
    Section(stringResource(R.string.ai_section_enable)) {
        SwitchRow(stringResource(R.string.ai_enable), if (ui.canEnable) null else stringResource(R.string.ai_enable_needs_checks),
            ui.settings.enabled && ui.canEnable, ui.canEnable || ui.settings.enabled, "ai-enable") { on ->
            if (on && !AiNotices.seen(context, AiNotices.ENABLE)) notice = true else vm.setEnabled(on)
        }
    }
    if (notice) AiEnableNotice({ notice = false }) { skip ->
        if (skip) AiNotices.mark(context, AiNotices.ENABLE)
        notice = false
        vm.setEnabled(true)
    }
}

/** What turning AI on means, worded like the privacy policy (keep the two in step, both languages). */
@Composable
fun AiEnableNotice(onCancel: () -> Unit, onAccept: (dontShowAgain: Boolean) -> Unit) {
    PolicyNoticeDialog(stringResource(R.string.ai_enable_notice_title),
        listOf(R.string.ai_notice_provider, R.string.ai_notice_when, R.string.ai_notice_sent, R.string.ai_notice_not_sent,
            R.string.ai_notice_storage, R.string.ai_notice_records, R.string.ai_notice_limit, R.string.ai_notice_cost,
            R.string.ai_notice_local).map { stringResource(it) },
        stringResource(R.string.ai_notice_disclaimer), stringResource(R.string.ai_chat_cancel), stringResource(R.string.ai_notice_skip),
        stringResource(R.string.ai_notice_enable), onCancel, onAccept,
        PolicyNoticeTags("ai-enable-notice", "ai-enable-notice-text", "ai-enable-notice-cancel", "ai-enable-notice-skip",
            "ai-enable-notice-ok"))
}

/** A secondary action on the AI page: a filled button, in red for one that deletes. */
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, destructive: Boolean = false) {
    FilledTonalButton(onClick, modifier.testTag(tag), enabled = enabled) {
        Text(label, color = if (destructive && enabled) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturesCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val context = LocalContext.current
    val s = ui.settings
    var pictureNotice by remember { mutableStateOf(false) }
    Section(stringResource(R.string.ai_section_features)) {
        SwitchRow(stringResource(R.string.ai_feature_bill_chat), stringResource(R.string.ai_feature_bill_chat_summary),
            s.billChat, true, "ai-feature-bill-chat") { on -> vm.setFeatures { it.copy(billChat = on) } }
        SwitchRow(stringResource(R.string.ai_feature_share_text), stringResource(R.string.ai_feature_share_text_summary),
            s.shareText, true, "ai-feature-share-text") { on -> vm.setFeatures { it.copy(shareText = on) } }
        SwitchRow(stringResource(R.string.ai_feature_share_image), stringResource(if (ui.imagePassed)
            R.string.ai_feature_share_image_summary else R.string.ai_feature_share_image_unavailable),
            s.shareImage && ui.imagePassed, ui.imagePassed, "ai-feature-share-image") { on ->
            if (on && !AiNotices.seen(context, AiNotices.SHARE_PICTURES)) pictureNotice = true
            else vm.setFeatures { it.copy(shareImage = on) }
        }
        SwitchRow(stringResource(R.string.ai_feature_thinking), stringResource(if (ui.thinkingPassed)
            R.string.ai_feature_thinking_summary else R.string.ai_feature_thinking_unavailable),
            s.thinking && ui.thinkingPassed, ui.thinkingPassed, "ai-feature-thinking") { on -> vm.setFeatures { it.copy(thinking = on) } }
        SwitchRow(stringResource(R.string.ai_feature_chat), stringResource(R.string.ai_feature_chat_summary),
            s.chat, true, "ai-feature-chat") { on -> vm.setFeatures { it.copy(chat = on) } }
        SwitchRow(stringResource(R.string.ai_feature_habits), stringResource(R.string.ai_feature_habits_summary),
            s.sendHabits, true, "ai-feature-habits") { on -> vm.setFeatures { it.copy(sendHabits = on) } }
    }
    if (pictureNotice) PictureNotice({ pictureNotice = false }) {
        AiNotices.mark(context, AiNotices.SHARE_PICTURES)
        pictureNotice = false
        vm.setFeatures { it.copy(shareImage = true) }
    }
}

@Composable
private fun DataCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val usage = ui.usage ?: return
    var confirm by remember { mutableStateOf(false) }
    val number = java.text.NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    Section(stringResource(R.string.ai_section_data)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_usage), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ai_usage_value, usage.requests, number.format(usage.tokens)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ai-usage"))
        }
        SecondaryButton(stringResource(R.string.ai_clear_conversations), { confirm = true }, "ai-clear-conversations",
            Modifier.fillMaxWidth(), destructive = true)
    }
    if (confirm) AlertDialog({ confirm = false },
        confirmButton = { TextButton({ confirm = false; vm.clearConversations() }, Modifier.testTag("ai-clear-confirm")) {
            Text(stringResource(R.string.ai_clear), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.ai_chat_cancel)) } },
        text = { Text(stringResource(R.string.ai_clear_conversations_confirm)) })
}

/** The line every AI page ends with. */
@Composable
fun AiDisclaimer(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.ai_disclaimer), modifier.fillMaxWidth().padding(horizontal = Space.sm).testTag("ai-disclaimer"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TextBox(label: String, value: String, onChange: (String) -> Unit, tag: String, enabled: Boolean,
    singleLine: Boolean = true, password: Boolean = false, placeholder: String? = null, supporting: String? = null,
    error: String? = null, monospace: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), enabled = enabled, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, label = { Text(label) }, shape = RoundedCornerShape(12.dp),
        placeholder = placeholder?.let { { Text(it) } }, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            singleLine && tag == "ai-base-url" -> KeyboardType.Uri
            else -> KeyboardType.Text
        }, autoCorrectEnabled = false))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val form = ui.form
    val enabled = !ui.busy
    var advanced by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.ai_section_connection)) {
        TextBox(stringResource(R.string.ai_base_url), form.baseUrl, { text -> vm.editConnection { it.copy(baseUrl = text) } },
            "ai-base-url", enabled, placeholder = stringResource(R.string.ai_base_url_hint),
            error = if (form.baseUrl.isNotBlank() && !ui.urlValid) stringResource(R.string.ai_base_url_invalid) else null,
            supporting = if (ui.urlValid && form.connection().cleartext) stringResource(R.string.ai_cleartext) else null)
        TextBox(stringResource(R.string.ai_api_key), form.apiKey, { text -> vm.editConnection { it.copy(apiKey = text.trim(), removeKey = false) } },
            "ai-api-key", enabled, password = true,
            // Always visible, unlike a placeholder: whether a key is saved matters while the field is untouched.
            supporting = when {
                form.removeKey -> stringResource(R.string.ai_api_key_removing)
                form.apiKey.isNotEmpty() -> null
                ui.keySaved -> stringResource(R.string.ai_api_key_saved)
                else -> stringResource(R.string.ai_api_key_none)
            })
        ModelField(ui, vm, enabled)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { advanced = !advanced }
            .testTag("ai-advanced").padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_advanced), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            ExpansionChevron(advanced)
        }
        val invalid = stringResource(R.string.ai_params_invalid)
        if (advanced) {
            Text(stringResource(R.string.ai_params_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                Triple(R.string.ai_extra_params, form.extraParams, "ai-extra-params") to { t: String -> vm.editConnection { it.copy(extraParams = t) } },
                Triple(R.string.ai_thinking_off_params, form.thinkingOffParams, "ai-thinking-off-params") to { t: String -> vm.editConnection { it.copy(thinkingOffParams = t) } },
                Triple(R.string.ai_thinking_on_params, form.thinkingOnParams, "ai-thinking-on-params") to { t: String -> vm.editConnection { it.copy(thinkingOnParams = t) } },
            ).forEach { (field, change) ->
                TextBox(stringResource(field.first), field.second, change, field.third, enabled, singleLine = false, monospace = true,
                    error = if (AiConnectionForm.validParams(field.second)) null else invalid)
            }
        } else if (listOf(form.extraParams, form.thinkingOffParams, form.thinkingOnParams).any { !AiConnectionForm.validParams(it) }) {
            Text(invalid, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (ui.saveFailed) Text(stringResource(R.string.ai_save_failed), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (ui.connectionDirty) Button({ vm.saveConnection() }, Modifier.fillMaxWidth().testTag("ai-connection-save"),
            enabled = enabled && ui.connectionValid) { Text(stringResource(R.string.ai_save)) }
    }
}

@Composable
private fun ModelField(ui: AiSettingsUi, vm: AiSettingsViewModel, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val models = ui.models
    LaunchedEffect(models) { if (!models.isNullOrEmpty()) open = true }
    Box {
        TextBox(stringResource(R.string.ai_model), ui.form.model, { text -> vm.editConnection { it.copy(model = text) } },
            "ai-model", enabled, placeholder = stringResource(R.string.ai_model_hint),
            error = if (ui.form.model.isBlank() && ui.form.baseUrl.isNotBlank()) stringResource(R.string.ai_model_required) else null)
        DropdownMenu(open && !models.isNullOrEmpty(), { open = false }, Modifier.testTag("ai-models")) {
            models.orEmpty().forEach { model ->
                DropdownMenuItem({ Text(model) }, { vm.editConnection { it.copy(model = model) }; open = false },
                    Modifier.testTag("ai-model-option-$model"))
            }
        }
    }
    // Side by side: the model list, and deleting the saved key while there is one.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(stringResource(if (ui.modelsLoading) R.string.ai_models_loading else R.string.ai_models_fetch),
            { if (!models.isNullOrEmpty()) open = true else vm.fetchModels() }, "ai-models-fetch", Modifier.weight(1f),
            enabled = enabled && ui.urlValid && !ui.modelsLoading)
        if (ui.keySaved && !ui.form.removeKey && ui.form.apiKey.isEmpty())
            SecondaryButton(stringResource(R.string.ai_api_key_remove), { vm.editConnection { it.copy(removeKey = true) } },
                "ai-api-key-remove", Modifier.weight(1f), enabled = enabled, destructive = true)
    }
    val problem = ui.modelsProblem
    when {
        problem != null -> Text(stringResource(R.string.ai_models_failed, problemText(problem, ui.form.connection().host)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai-models-problem"))
        models != null && models.isEmpty() -> Text(stringResource(R.string.ai_models_empty), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChecksCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val report = ui.report
    Section(stringResource(R.string.ai_section_checks)) {
        Text(stringResource(R.string.ai_checks_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AiCheckItem.entries.forEach { item ->
            val check = report?.of(item) ?: AiCheck()
            val running = ui.checking == item
            val required = item == AiCheckItem.CONNECT || item == AiCheckItem.TOOLS
            Column(Modifier.fillMaxWidth().testTag("ai-check-${item.name}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(itemName(item)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (required) R.string.ai_check_required else R.string.ai_check_optional),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(Space.sm))
                    Text(stringResource(if (running) R.string.ai_state_running else stateName(item, check.state)),
                        Modifier.testTag("ai-check-state-${item.name}"), style = MaterialTheme.typography.bodyMedium,
                        color = if (running) MaterialTheme.colorScheme.onSurfaceVariant else checkColor(check.state))
                }
                val problem = check.problem
                if (!running && problem != null && check.state != AiCheckState.PASSED)
                    Text(problemText(problem, ui.settings.connection.host), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (report != null && ui.checking == null) Text(stringResource(R.string.ai_checked_at, Instant.ofEpochMilli(report.checkedAtMs)
            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(vm::check, Modifier.fillMaxWidth().testTag("ai-check"),
            enabled = !ui.busy && (if (ui.connectionDirty) ui.connectionValid else ui.settings.connection.usable)) {
            Text(stringResource(when {
                ui.connectionDirty -> R.string.ai_check_save_and_run
                report == null -> R.string.ai_check_run
                else -> R.string.ai_check_rerun
            }))
        }
    }
}

@Composable
private fun DefaultsCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val settings = ui.settings
    val english = LocalConfiguration.current.locales[0].language != "zh"
    Section(stringResource(R.string.ai_section_defaults)) {
        Text(stringResource(R.string.ai_defaults_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val minutes = settings.defaultTimeMinutes
        TimeField(stringResource(R.string.ai_default_time), "%02d:%02d".format(minutes / 60, minutes % 60), { text ->
            runCatching { java.time.LocalTime.parse(text) }.getOrNull()?.let { vm.saveDefaults(timeMinutes = it.hour * 60 + it.minute) }
        })
        val none = stringResource(R.string.ai_default_account_none)
        val accounts = ui.choices.accounts
        ChoiceField(stringResource(R.string.ai_default_account),
            settings.defaultCashAccountId?.takeIf { id -> accounts.any { it.id == id } }?.toString() ?: "",
            listOf("" to none) + accounts.map { it.id.toString() to "${it.name} · ${it.currencyCode}" },
            { key -> vm.saveDefaults(cashAccountId = key.toLongOrNull()) })
        listOf(BillCategoryKind.EXPENSE, BillCategoryKind.INCOME).forEach { kind ->
            val options = ui.choices.categories.filter { it.kind == kind && it.parentId == null && !it.hidden }
            // The catch-all stands for "no choice", so a later rename or reorder never strands the default.
            val catchAll = options.firstOrNull { it.builtinKey == if (kind == BillCategoryKind.EXPENSE) "other" else "income.other" }
            val chosen = (if (kind == BillCategoryKind.EXPENSE) settings.defaultExpenseCategoryId else settings.defaultIncomeCategoryId)
                ?.takeIf { id -> options.any { it.id == id } } ?: catchAll?.id
            ChoiceField(stringResource(if (kind == BillCategoryKind.EXPENSE) R.string.ai_default_expense else R.string.ai_default_income),
                chosen?.toString() ?: "", options.map { it.id.toString() to it.name(english) }, { key ->
                    val id = key.toLongOrNull()?.takeIf { it != catchAll?.id }
                    if (kind == BillCategoryKind.EXPENSE) vm.saveDefaults(expenseCategoryId = id) else vm.saveDefaults(incomeCategoryId = id)
                })
        }
    }
}

@Composable
private fun PersonalCard(ui: AiSettingsUi, vm: AiSettingsViewModel) {
    val personal = ui.personal
    val enabled = !ui.saving
    Section(stringResource(R.string.ai_section_personal)) {
        TextBox(stringResource(R.string.ai_my_rules), personal.myRules, { text -> vm.editPersonal { it.copy(myRules = text.take(AiRules.MY_RULES_MAX)) } },
            "ai-my-rules", enabled, singleLine = false, placeholder = stringResource(R.string.ai_my_rules_hint),
            supporting = stringResource(R.string.ai_my_rules_count, personal.myRules.length, AiRules.MY_RULES_MAX))
        if (personal.myRules.isNotEmpty()) AiTonalButton(stringResource(R.string.ai_my_rules_reset),
            { vm.editPersonal { it.copy(myRules = "") } }, Modifier.testTag("ai-my-rules-reset"), enabled = enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Box(Modifier.weight(1f)) {
                TextBox(stringResource(R.string.ai_user_nickname), personal.userNickname,
                    { text -> vm.editPersonal { it.copy(userNickname = text.take(AiRules.NICKNAME_MAX)) } }, "ai-user-nickname", enabled)
            }
            Box(Modifier.weight(1f)) {
                TextBox(stringResource(R.string.ai_ai_nickname), personal.aiNickname,
                    { text -> vm.editPersonal { it.copy(aiNickname = text.take(AiRules.NICKNAME_MAX)) } }, "ai-ai-nickname", enabled)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            AvatarPicker(vm, AiAvatar.USER, personal.userNickname, Modifier.weight(1f))
            AvatarPicker(vm, AiAvatar.AI, personal.aiNickname.ifBlank { "AI" }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.ai_personal_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.personalDirty) Button(vm::savePersonal, Modifier.fillMaxWidth().testTag("ai-personal-save"),
            enabled = enabled && ui.personalValid) { Text(stringResource(R.string.ai_save)) }
    }
}

@Composable
private fun AvatarPicker(vm: AiSettingsViewModel, who: AiAvatar, name: String, modifier: Modifier) {
    val context = LocalContext.current
    val version by vm.avatars.version.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf(false) }
    val image = remember(version) { vm.avatars.file(who)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        failed = false
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> input.readNBytes(MAX_PICTURE + 1).takeIf { it.size <= MAX_PICTURE } }
        }.getOrNull()
        vm.setAvatar(who, bytes) { failed = true }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AiAvatarImage(image, name, Modifier.size(56.dp).clip(CircleShape).clickable(role = Role.Button) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.testTag("ai-avatar-${who.name}"))
        Text(stringResource(if (who == AiAvatar.USER) R.string.ai_avatar_user else R.string.ai_avatar_ai),
            style = MaterialTheme.typography.bodySmall)
        if (image != null) AiTonalButton(stringResource(R.string.ai_avatar_reset), { vm.clearAvatar(who) },
            Modifier.testTag("ai-avatar-reset-${who.name}"))
        if (failed) Text(stringResource(R.string.ai_avatar_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

/** A picture, or the first letter of [name] on the brand tint. */
@Composable
fun AiAvatarImage(image: androidx.compose.ui.graphics.ImageBitmap?, name: String, modifier: Modifier) {
    if (image != null) Image(image, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).ifEmpty { "?" }.uppercase(), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private const val MAX_PICTURE = 20 * 1024 * 1024

private fun itemName(item: AiCheckItem) = when (item) {
    AiCheckItem.CONNECT -> R.string.ai_check_connect
    AiCheckItem.TOOLS -> R.string.ai_check_tools
    AiCheckItem.IMAGE -> R.string.ai_check_image
    AiCheckItem.THINKING -> R.string.ai_check_thinking
}

/**
 * A check result's colour. Fixed green for passed, whatever the gain and loss colours are set to: with red for gains a
 * passed check must still not read as red.
 */
@Composable
internal fun checkColor(state: AiCheckState): androidx.compose.ui.graphics.Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (state) {
        AiCheckState.PASSED -> androidx.compose.ui.graphics.Color(if (dark) 0xFF49C996 else 0xFF168457)
        AiCheckState.FAILED -> MaterialTheme.colorScheme.error
        AiCheckState.UNKNOWN -> androidx.compose.ui.graphics.Color(if (dark) 0xFFF2B84B else 0xFFB26A00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun stateName(item: AiCheckItem, state: AiCheckState) = when (state) {
    AiCheckState.NOT_CHECKED -> R.string.ai_state_not_checked
    AiCheckState.PASSED -> R.string.ai_state_passed
    AiCheckState.FAILED -> if (item == AiCheckItem.CONNECT) R.string.ai_state_connect_failed else R.string.ai_state_failed
    AiCheckState.UNKNOWN -> R.string.ai_state_unknown
    AiCheckState.NOT_CONFIGURED -> R.string.ai_state_not_configured
}

@Composable
internal fun problemText(problem: AiProblem, host: String): String = when (problem) {
    AiProblem.OFFLINE -> stringResource(R.string.ai_problem_offline, host)
    AiProblem.TIMEOUT -> stringResource(R.string.ai_problem_timeout)
    AiProblem.UNAUTHORIZED -> stringResource(R.string.ai_problem_unauthorized)
    AiProblem.PAYMENT -> stringResource(R.string.ai_problem_payment)
    AiProblem.NOT_FOUND -> stringResource(R.string.ai_problem_not_found)
    AiProblem.RATE_LIMITED -> stringResource(R.string.ai_problem_rate_limited)
    AiProblem.SERVER -> stringResource(R.string.ai_problem_server)
    AiProblem.REJECTED -> stringResource(R.string.ai_problem_rejected)
    AiProblem.BAD_RESPONSE -> stringResource(R.string.ai_problem_bad_response)
    AiProblem.NO_TOOL_CALL -> stringResource(R.string.ai_problem_no_tool_call)
    AiProblem.WRONG_ANSWER -> stringResource(R.string.ai_problem_wrong_answer)
    AiProblem.NO_THINKING -> stringResource(R.string.ai_problem_no_thinking)
    AiProblem.NO_KEY_STORAGE -> stringResource(R.string.ai_problem_no_key_storage)
    AiProblem.THINKING_STILL_ON -> stringResource(R.string.ai_problem_thinking_still_on)
    AiProblem.UNREADABLE_DRAFTS -> stringResource(R.string.ai_problem_unreadable_drafts)
}

/** The settings entry's summary line. */
@Composable
fun aiSummaryText(report: AiCheckReport?, host: String): String = when {
    report == null -> stringResource(R.string.ai_summary_unchecked)
    report.usable -> stringResource(R.string.ai_summary_ready, host)
    else -> stringResource(R.string.ai_summary_failed)
}
