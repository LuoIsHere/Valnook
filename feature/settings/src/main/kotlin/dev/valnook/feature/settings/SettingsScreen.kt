package dev.valnook.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.valnook.designsystem.ActionButton
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.ChoiceField
import dev.valnook.designsystem.CurrencyChoice
import dev.valnook.designsystem.Field
import dev.valnook.designsystem.GlassTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.domain.model.NavigationItemId
import kotlinx.coroutines.launch
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.RowChevron
import dev.valnook.designsystem.FormDivider

@Composable
fun SettingsHome(
    vm: SettingsViewModel,
    demoMode: Boolean,
    switching: Boolean,
    switchFailed: Boolean,
    onRates: () -> Unit,
    onDemoChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onNavigation: () -> Unit,
    onBackupExport: () -> Unit,
    onWebAdmin: () -> Unit,
    onHiddenPage: (NavigationItemId) -> Unit,
    versionName: String,
    internalBuildId: String,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeChange: suspend (AppThemeMode) -> Boolean = { false },
    onAbout: () -> Unit = {},
    onCardFaces: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var appearancePicker by rememberSaveable { mutableStateOf<String?>(null) }
    if (!state.loaded) {
        if (state.loadFailed) Text(stringResource(R.string.settings_load_failed)) else CircularProgressIndicator()
        return
    }
    AppearanceSettingsDialog(vm, language = true, visible = appearancePicker == "language", onClose = { appearancePicker = null })
    AppearanceSettingsDialog(vm, language = false, visible = appearancePicker == "colors", onClose = { appearancePicker = null })
    LazyColumn(Modifier.fillMaxSize().testTag("settings-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        item {
            GlassCard {
            // One entry for one page: the base currency is set there with the rates, so both are summed up here.
            val rates = stringResource(if (state.automatic) R.string.settings_fx_auto_on else R.string.settings_fx_default)
            SettingEntry(stringResource(R.string.settings_exchange_rates),
                state.savedSettings.baseCurrency?.let { stringResource(R.string.settings_fx_summary, it.code, rates) }
                    ?: stringResource(R.string.settings_fx_summary_unset, rates), onRates, Modifier.testTag("settings-fx"))
            }
        }
        item {
            GlassCard {
            ThemeSetting(themeMode, onThemeChange)
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            if (!demoMode) {
            SettingEntry(stringResource(R.string.settings_language), languageLabel(state.savedSettings.language),
                { vm.discardAndReload(); appearancePicker = "language" }, Modifier.testTag("settings-language"))
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            }
            SettingEntry(stringResource(R.string.settings_gain_loss_colors),
                colorLabel(state.savedSettings.gainLossColors),
                { vm.discardAndReload(); appearancePicker = "colors" }, Modifier.testTag("settings-colors"))
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            SettingEntry(stringResource(R.string.settings_navigation),
                stringResource(R.string.settings_navigation_summary), onNavigation)
            }
        }
        item {
            GlassCard {
            SettingEntry(stringResource(R.string.settings_card_faces),
                stringResource(R.string.settings_card_faces_summary), onCardFaces,
                Modifier.testTag("settings-card-faces"))
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            SettingEntry(stringResource(R.string.settings_backup_export),
                if (demoMode) stringResource(R.string.settings_backup_export_demo_summary)
                else stringResource(R.string.settings_backup_export_summary), onBackupExport,
                Modifier.testTag("settings-backup-export"))
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            SettingEntry(stringResource(R.string.settings_webadmin),
                stringResource(R.string.settings_webadmin_summary), onWebAdmin,
                Modifier.testTag("settings-webadmin"))
            }
        }
        if (state.savedSettings.navigation.hiddenInOrder.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text(stringResource(R.string.settings_hidden_pages),
                            modifier = Modifier.padding(horizontal = Space.cardInset, vertical = Space.md),
                            style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                        state.savedSettings.navigation.hiddenInOrder.forEachIndexed { index, item ->
                            SettingEntry(navigationLabel(item), stringResource(R.string.settings_hidden_page_summary),
                                { onHiddenPage(item) })
                            if (index < state.savedSettings.navigation.hiddenInOrder.lastIndex) HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_demo_mode), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_demo_summary), style = MaterialTheme.typography.bodySmall)
                    if (switching) Text(stringResource(R.string.settings_demo_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    if (switchFailed) Text(stringResource(R.string.settings_demo_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Switch(demoMode, { onDemoChange(it) }, enabled = !switching,
                    modifier = Modifier.testTag("settings-demo-switch"))
            }
        }
        item {
            GlassCard {
                SettingEntry(stringResource(R.string.settings_about),
                    stringResource(R.string.settings_about_summary), onAbout, Modifier.testTag("settings-about"))
            }
        }
        if (!demoMode) {
            item { HorizontalDivider(Modifier.padding(top = Space.lg)) }
            item { Text(stringResource(R.string.settings_danger_zone), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium) }
            item { TextButton(onClick = onClear) { Text(stringResource(R.string.settings_clear_data),
                color = MaterialTheme.colorScheme.error) } }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.settings_footer_copyright), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_license), Modifier.testTag("settings-footer-license"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_version, versionName), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_internal, internalBuildId), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SettingEntry(title: String, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.cardInset, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) Box(Modifier.padding(end = Space.md)) { leadingIcon() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowChevron()
    }
}

/** Saving sits in the root toolbar as a ✓, as on the navigation page; [onToolbarChange] hands it over. */
@Composable
fun FxSettingsScreen(vm: SettingsViewModel, onToolbarChange: (FxToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    DisposableEffect(onToolbarChange) { onDispose { onToolbarChange(null) } }
    if (!state.loaded) {
        SideEffect { onToolbarChange(null) }
        if (state.loadFailed) Text(stringResource(R.string.settings_load_failed)) else CircularProgressIndicator()
        return
    }
    val base = state.shownBase
    // Automatic rates save themselves, so the ✓ goes away; by hand it waits for a change.
    val toolbar = if (state.automatic) null else FxToolbarState(state.busy, state.dirty, state.dirty && base != null, vm::saveRates)
    SideEffect { onToolbarChange(toolbar) }
    val listState = rememberLazyListState()
    // The ✓ is at the top and the message at the end of the list, so a failed save scrolls to its reason.
    LaunchedEffect(state.error) { if (state.error != null) listState.animateScrollToItem(2) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        item { AutomaticRates(state, vm) }
        item {
            FxRateEditor(base, state.shownRows, state.busy, vm::selectBase, vm::addRate,
                vm::updateRow, vm::removeRate, readOnly = state.automatic, notes = sourceDateNotes(state),
                showDefaultHint = !state.automatic,
                emptyText = if (state.automatic) stringResource(R.string.settings_fx_auto_empty) else null)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                state.error?.let { Text(settingsErrorMessage(it), Modifier.testTag("fx-error"), color = MaterialTheme.colorScheme.error) }
                if (state.error == ErrorCode.STALE_RECORD) TextButton(vm::discardAndReload) {
                    Text(stringResource(R.string.settings_reload_draft))
                }
                if (state.saved && !state.automatic) Text(stringResource(R.string.settings_saved),
                    Modifier.padding(horizontal = 4.dp).testTag("fx-saved"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    when (state.autoPrompt) {
        AutoRatesPrompt.DISCARD_CHANGES -> FxDiscardDialog(vm::cancelAutomatic, vm::discardForAutomatic)
        AutoRatesPrompt.NETWORK_NOTICE -> FxNetworkNoticeDialog(vm::cancelAutomatic, vm::acceptNetworkNotice)
        null -> Unit
    }
}

/** One card: the switch, then (when on) the status line with its button and currencies the last sync had no rate for. */
@Composable
private fun AutomaticRates(state: SettingsUiState, vm: SettingsViewModel) {
    val fx = state.fx
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        GlassCard {
            // The whole line toggles, as on the account edit page.
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("fx-auto-switch")
                .toggleable(fx.autoEnabled, enabled = fx.available && !state.busy, role = Role.Switch,
                    onValueChange = vm::requestAutomatic)
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.settings_fx_auto), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (fx.available) R.string.settings_fx_auto_summary else R.string.settings_fx_demo),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(fx.autoEnabled, onCheckedChange = null, enabled = fx.available && !state.busy)
            }
            if (state.automatic) {
                FormDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = Space.xs, top = Space.xs, bottom = Space.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(fxStatusLine(fx, locale), Modifier.weight(1f).testTag("fx-auto-status"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fx.lastError != null && !fx.running) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    // A stored day is never fetched again, so once today is in there is nothing left to update.
                    val updatedToday = fx.lastSuccessDay == java.time.LocalDate.now()
                    TextButton(vm::refreshRates, enabled = !fx.running && !state.busy && !updatedToday,
                        modifier = Modifier.testTag("fx-update-now")) {
                        Text(stringResource(if (updatedToday) R.string.settings_fx_updated_today else R.string.settings_fx_update_now),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (fx.missingCurrencies.isNotEmpty()) Text(
                    stringResource(R.string.settings_fx_missing, fx.missingCurrencies.joinToString(", ")),
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = Space.md).testTag("fx-missing"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.automatic) Text(stringResource(R.string.settings_fx_readonly), Modifier.padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Short enough to share the row with its button: month and day without the year, and only the time for today. */
@Composable
private fun fxStatusLine(fx: dev.valnook.domain.fx.FxSyncStatus, locale: java.util.Locale): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
    val day = java.time.format.DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    fun moment(ms: Long): String {
        val at = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        return if (at.toLocalDate() == java.time.LocalDate.now(zone)) at.format(time) else at.format(day) + " " + at.format(time)
    }
    return when {
        fx.running -> stringResource(R.string.settings_fx_status_running)
        fx.lastError != null && fx.lastAttemptAtMs != null -> stringResource(R.string.settings_fx_status_failed,
            moment(fx.lastAttemptAtMs!! + 30 * 60_000L))
        fx.lastSuccessAtMs == null -> stringResource(R.string.settings_fx_status_never)
        else -> stringResource(R.string.settings_fx_status_ok, fx.dataDate?.format(day) ?: "—", moment(fx.lastSuccessAtMs!!))
    }
}

/** In automatic mode, rows whose source published an older value than the headline data date name their own date. */
@Composable
private fun sourceDateNotes(state: SettingsUiState): Map<String, String> {
    val headline = state.fx.dataDate
    if (!state.automatic || headline == null) return emptyMap()
    val format = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0])
    return state.fx.sourceDates.filterValues { it != headline }
        .mapValues { stringResource(R.string.settings_fx_row_date, it.value.format(format)) }
}

@Composable
fun LanguageSettingsScreen(vm: SettingsViewModel, onClose: () -> Unit = {}) {
    AppearanceSettingsDialog(vm, language = true, visible = true, onClose = onClose)
}

@Composable
fun GainLossColorsScreen(vm: SettingsViewModel, onClose: () -> Unit = {}) {
    AppearanceSettingsDialog(vm, language = false, visible = true, onClose = onClose)
}

@Composable
fun ClearDataScreen(
    challenge: String,
    onConfirm: suspend (String) -> Unit,
    onCancel: () -> Unit,
    onExpired: () -> Unit
) {
    var input by remember(challenge) { mutableStateOf("") }
    var busy by remember(challenge) { mutableStateOf(false) }
    var failed by remember(challenge) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(challenge) { onDispose { if (!busy) onCancel() } }
    DisposableEffect(lifecycleOwner, challenge, busy) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !busy) {
                onCancel()
                onExpired()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(stringResource(R.string.settings_clear_warning), color = MaterialTheme.colorScheme.error) }
        item { Text(stringResource(R.string.settings_clear_scope)) }
        item { Text(challenge, style = MaterialTheme.typography.headlineSmall) }
        item { GlassTextField(input, { input = it }, stringResource(R.string.settings_clear_input),
            Modifier.fillMaxWidth(), enabled = !busy, keyboardType = KeyboardType.Number) }
        if (failed) item { Text(stringResource(R.string.settings_clear_failed), color = MaterialTheme.colorScheme.error) }
        item {
            Button(onClick = {
                busy = true
                failed = false
                scope.launch {
                    runCatching { onConfirm(input) }.onFailure { failed = true; busy = false }
                }
            }, enabled = !busy && input == challenge, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) stringResource(R.string.settings_clearing) else stringResource(R.string.settings_confirm_clear))
            }
        }
    }
}

@Composable
fun languageLabel(value: AppLanguage): String = stringResource(when (value) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.ZH_HANS -> R.string.settings_language_zh
    AppLanguage.ENGLISH -> R.string.settings_language_en
})

@Composable
internal fun colorLabel(value: GainLossColorScheme): String = stringResource(when (value) {
    GainLossColorScheme.GREEN_GAIN -> R.string.settings_green_gain
    GainLossColorScheme.RED_GAIN -> R.string.settings_red_gain
})

@Composable
fun settingsErrorMessage(error: ErrorCode): String = stringResource(when (error) {
    ErrorCode.CURRENCY -> R.string.settings_error_currency
    ErrorCode.FORMAT -> R.string.settings_error_format
    ErrorCode.PRECISION -> R.string.settings_error_precision
    ErrorCode.OVERFLOW -> R.string.settings_error_overflow
    ErrorCode.POSITIVE -> R.string.settings_error_positive
    ErrorCode.DUPLICATE_CURRENCY -> R.string.settings_error_duplicate_currency
    ErrorCode.STALE_RECORD -> R.string.settings_error_conflict
    ErrorCode.SESSION_EXPIRED -> R.string.settings_error_session_expired
    else -> R.string.settings_error_generic
})
