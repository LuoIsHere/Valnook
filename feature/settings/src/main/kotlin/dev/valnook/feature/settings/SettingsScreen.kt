package dev.valnook.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.valnook.designsystem.ActionButton
import dev.valnook.designsystem.ChoiceField
import dev.valnook.designsystem.CurrencyChoice
import dev.valnook.designsystem.Field
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.domain.model.NavigationItemId
import kotlinx.coroutines.launch

@Composable
fun SettingsHome(
    vm: SettingsViewModel,
    demoMode: Boolean,
    switching: Boolean,
    switchFailed: Boolean,
    onRates: () -> Unit,
    onLanguage: () -> Unit,
    onColors: () -> Unit,
    onDemoChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onNavigation: () -> Unit,
    onHiddenPage: (NavigationItemId) -> Unit,
    versionName: String,
    internalBuildId: String
) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) {
        if (state.loadFailed) Text(stringResource(R.string.settings_load_failed)) else CircularProgressIndicator()
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        item {
            SettingEntry(stringResource(R.string.settings_base_currency),
                state.savedSettings.baseCurrency?.code ?: stringResource(R.string.settings_not_set), onRates)
        }
        item {
            SettingEntry(stringResource(R.string.settings_exchange_rates),
                stringResource(R.string.settings_fx_default), onRates)
        }
        if (!demoMode) item {
            SettingEntry(stringResource(R.string.settings_language), languageLabel(state.savedSettings.language), onLanguage)
        }
        item {
            SettingEntry(stringResource(R.string.settings_gain_loss_colors),
                colorLabel(state.savedSettings.gainLossColors), onColors)
        }
        item {
            SettingEntry(stringResource(R.string.settings_navigation),
                stringResource(R.string.settings_navigation_summary), onNavigation)
        }
        if (state.savedSettings.navigation.hiddenInOrder.isNotEmpty()) {
            item { Text(stringResource(R.string.settings_hidden_pages), style = MaterialTheme.typography.titleMedium) }
            items(state.savedSettings.navigation.hiddenInOrder, key = { it.name }) { item ->
                SettingEntry(navigationLabel(item), stringResource(R.string.settings_hidden_page_summary)) {
                    onHiddenPage(item)
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
                Switch(demoMode, { onDemoChange(it) }, enabled = !switching)
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
                Text(stringResource(R.string.settings_footer_version, versionName), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_footer_internal, internalBuildId), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun NavigationSettingsScreen(vm: NavigationSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val moveUp = stringResource(R.string.settings_move_up)
    val moveDown = stringResource(R.string.settings_move_down)
    val reorder = stringResource(R.string.settings_reorder)
    val visibleLabel = stringResource(R.string.settings_visible)
    val hiddenLabel = stringResource(R.string.settings_hidden)
    if (!state.loaded) {
        CircularProgressIndicator()
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (state.editing) {
                    TextButton(vm::cancel, enabled = !state.busy) { Text(stringResource(R.string.settings_cancel)) }
                    Button(vm::save, enabled = !state.busy) { Text(stringResource(R.string.settings_save)) }
                } else Button(vm::edit) { Text(stringResource(R.string.settings_edit)) }
            }
        }
        itemsIndexed(state.draft.order, key = { _, item -> item.name }) { index, item ->
            Row(Modifier.fillMaxWidth().testTag("navigation-row-${item.name.lowercase()}")
                .padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(navigationLabel(item), Modifier.weight(1f).padding(horizontal = Space.md),
                    style = MaterialTheme.typography.titleMedium)
                IconButton({ vm.toggle(item) }, enabled = state.editing && item != NavigationItemId.SETTINGS,
                    modifier = Modifier.testTag("navigation-visible-${item.name.lowercase()}")) {
                    val visible = item in state.draft.visible
                    Icon(if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = null, modifier = Modifier.semantics {
                        contentDescription = if (item in state.draft.visible) visibleLabel else hiddenLabel
                    })
                }
                Text("≡", style = MaterialTheme.typography.titleLarge,
                    color = if (state.editing) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.semantics {
                        contentDescription = reorder
                        customActions = listOf(
                            CustomAccessibilityAction(moveUp) {
                                if (state.editing && index > 0) vm.move(item, -1)
                                state.editing && index > 0
                            },
                            CustomAccessibilityAction(moveDown) {
                                if (state.editing && index < state.draft.order.lastIndex) vm.move(item, 1)
                                state.editing && index < state.draft.order.lastIndex
                            })
                    }.pointerInput(state.editing, item, index) {
                        if (!state.editing) return@pointerInput
                        var accumulated = 0f
                        val threshold = 40.dp.toPx()
                        detectVerticalDragGestures(
                            onDragEnd = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f },
                            onVerticalDrag = { change, distance ->
                                change.consume()
                                accumulated += distance
                                if (accumulated >= threshold) {
                                    vm.move(item, 1)
                                    accumulated = 0f
                                } else if (accumulated <= -threshold) {
                                    vm.move(item, -1)
                                    accumulated = 0f
                                }
                            }
                        )
                    })
            }
            HorizontalDivider()
        }
        state.error?.let { error -> item {
            Column {
                Text(stringResource(R.string.settings_error_conflict), color = MaterialTheme.colorScheme.error)
                if (error == ErrorCode.STALE_RECORD) TextButton(vm::discardAndReload) {
                    Text(stringResource(R.string.settings_reload_draft))
                }
            }
        } }
        if (state.savedNotice) item { Text(stringResource(R.string.settings_saved)) }
    }
}

@Composable
private fun SettingEntry(title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(Space.md),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun FxSettingsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) {
        if (state.loadFailed) Text(stringResource(R.string.settings_load_failed)) else CircularProgressIndicator()
        return
    }
    val base = state.settings.baseCurrency
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(stringResource(R.string.settings_base_currency), style = MaterialTheme.typography.titleLarge) }
        item { CurrencyChoice(base?.code.orEmpty(), { vm.selectBase(Currency.of(it)) }, !state.busy,
            Currency.supported.map { it.code to it.name }) }
        item { Text(if (base == null) stringResource(R.string.settings_no_base) else
            stringResource(R.string.settings_fx_direction, base.code)) }
        itemsIndexed(state.rows, key = { _, row -> row.sourceCurrency.code }) { index, row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    val currencyField: @Composable () -> Unit = {
                        CurrencyChoice(row.sourceCurrency.code, { vm.updateRow(index, source = Currency.of(it)) },
                            !state.busy, Currency.supported.map { it.code to it.name },
                            state.rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() +
                                listOfNotNull(base?.code))
                    }
                    val rateField: @Composable () -> Unit = {
                        Field(stringResource(R.string.settings_rate), row.rateInput,
                            { vm.updateRow(index, rate = it) }, numeric = true, enabled = !state.busy)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.3f) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f)) { currencyField() }
                                Box(Modifier.weight(1f)) { rateField() }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                currencyField()
                                rateField()
                            }
                        }
                    }
                    TextButton({ vm.removeRate(index) }, enabled = !state.busy) {
                        Text(stringResource(R.string.settings_remove_rate))
                    }
                }
            }
        }
        item { ActionButton(vm::addRate, enabled = !state.busy && base != null) {
            Text(stringResource(R.string.settings_add_rate))
        } }
        item { Text(stringResource(R.string.settings_fx_default)) }
        item {
            state.error?.let { Text(settingsErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            if (state.error == ErrorCode.STALE_RECORD) TextButton(vm::discardAndReload) {
                Text(stringResource(R.string.settings_reload_draft))
            }
            if (state.saved) Text(stringResource(R.string.settings_saved))
            Button(vm::saveRates, enabled = !state.busy && base != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) stringResource(R.string.settings_saving) else stringResource(R.string.settings_save))
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            ChoiceField(stringResource(R.string.settings_language), state.settings.language.name,
                AppLanguage.entries.map { it.name to languageLabel(it) },
                { vm.selectLanguage(AppLanguage.valueOf(it)) }, !state.busy)
        }
        item {
            state.error?.let { Text(settingsErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            if (state.saved) Text(stringResource(R.string.settings_saved))
            Button(vm::saveLanguage, enabled = !state.busy && state.dirty,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) stringResource(R.string.settings_saving)
                    else stringResource(R.string.settings_apply))
            }
        }
    }
}

@Composable
fun GainLossColorsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            ChoiceField(stringResource(R.string.settings_gain_loss_colors), state.settings.gainLossColors.name,
                GainLossColorScheme.entries.map { it.name to colorLabel(it) },
                { vm.selectGainLossColors(GainLossColorScheme.valueOf(it)) }, !state.busy)
        }
        item {
            val savedColors = LocalGainLossPalette.current
            val colors = if (state.settings.gainLossColors == state.savedSettings.gainLossColors) savedColors else
                dev.valnook.designsystem.GainLossPalette(savedColors.loss, savedColors.gain, savedColors.neutral)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(stringResource(R.string.settings_color_gain), color = colors.gain)
                Text(stringResource(R.string.settings_color_loss), color = colors.loss)
                Text(stringResource(R.string.settings_color_zero), color = colors.neutral)
            }
        }
        item {
            state.error?.let { Text(settingsErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            if (state.saved) Text(stringResource(R.string.settings_saved))
            Button(vm::saveGainLossColors, enabled = !state.busy && state.dirty,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (state.busy) stringResource(R.string.settings_saving)
                    else stringResource(R.string.settings_apply))
            }
        }
    }
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
        item { Field(stringResource(R.string.settings_clear_input), input, { input = it }, enabled = !busy) }
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
private fun languageLabel(value: AppLanguage): String = stringResource(when (value) {
    AppLanguage.SYSTEM -> R.string.settings_language_system
    AppLanguage.ZH_HANS -> R.string.settings_language_zh
    AppLanguage.ENGLISH -> R.string.settings_language_en
})

@Composable
private fun colorLabel(value: GainLossColorScheme): String = stringResource(when (value) {
    GainLossColorScheme.GREEN_GAIN -> R.string.settings_green_gain
    GainLossColorScheme.RED_GAIN -> R.string.settings_red_gain
})

@Composable
private fun navigationLabel(value: NavigationItemId): String = stringResource(when (value) {
    NavigationItemId.ACCOUNTS -> R.string.settings_nav_accounts
    NavigationItemId.INVESTMENTS -> R.string.settings_nav_investments
    NavigationItemId.STATISTICS -> R.string.settings_nav_statistics
    NavigationItemId.SETTINGS -> R.string.settings_nav_settings
})

@Composable
private fun settingsErrorMessage(error: ErrorCode): String = stringResource(when (error) {
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
