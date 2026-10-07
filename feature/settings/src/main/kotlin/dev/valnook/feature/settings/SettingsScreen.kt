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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
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
    onThemeChange: suspend (AppThemeMode) -> Boolean = { false }
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var appearancePicker by rememberSaveable { mutableStateOf<String?>(null) }
    if (!state.loaded) {
        if (state.loadFailed) Text(stringResource(R.string.settings_load_failed)) else CircularProgressIndicator()
        return
    }
    AppearanceSettingsDialog(vm, language = true, visible = appearancePicker == "language", onClose = { appearancePicker = null })
    AppearanceSettingsDialog(vm, language = false, visible = appearancePicker == "colors", onClose = { appearancePicker = null })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        item {
            GlassCard {
            SettingEntry(stringResource(R.string.settings_base_currency),
                state.savedSettings.baseCurrency?.code ?: stringResource(R.string.settings_not_set), onRates)
            HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
            SettingEntry(stringResource(R.string.settings_exchange_rates),
                stringResource(R.string.settings_fx_default), onRates)
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
fun NavigationSettingsScreen(vm: NavigationSettingsViewModel,
    onToolbarChange: (NavigationToolbarState?) -> Unit = {}) {
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
    val listState = rememberLazyListState()
    var localOrder by remember { mutableStateOf(state.draft.order) }
    var draggedItem by remember { mutableStateOf<NavigationItemId?>(null) }
    var dragStartOffset by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val toolbar = remember(vm, state, draggedItem) {
        NavigationToolbarState(state.editing, state.busy, draggedItem != null,
            state.draft != state.saved, vm::edit, vm::cancel, vm::save)
    }
    androidx.compose.runtime.SideEffect { onToolbarChange(toolbar) }
    DisposableEffect(onToolbarChange) { onDispose { onToolbarChange(null) } }

    LaunchedEffect(state.draft.order, draggedItem) {
        if (draggedItem == null && localOrder != state.draft.order) localOrder = state.draft.order
    }

    fun finishDrag(commit: Boolean) {
        val item = draggedItem
        if (item != null && commit) {
            val from = state.draft.order.indexOf(item)
            val to = localOrder.indexOf(item)
            if (from >= 0 && to >= 0 && from != to) vm.move(item, to - from)
        } else if (!commit) {
            localOrder = state.draft.order
        }
        draggedItem = null
        dragStartOffset = 0f
        dragDistance = 0f
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        itemsIndexed(localOrder, key = { _, item -> item.name }) { index, item ->
            val dragging = draggedItem == item
            Column(Modifier.fillMaxWidth()
                .animateItem(fadeInSpec = null,
                    placementSpec = if (dragging) null else tween(durationMillis = 140), fadeOutSpec = null)
                .graphicsLayer {
                    val currentOffset = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == item.name }?.offset?.toFloat() ?: dragStartOffset
                    translationY = if (dragging) dragStartOffset + dragDistance - currentOffset else 0f
                }
                .zIndex(if (dragging) 1f else 0f)) {
                Surface(color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surface,
                    tonalElevation = if (dragging) 4.dp else 0.dp) {
                    Row(Modifier.fillMaxWidth().testTag("navigation-row-${item.name.lowercase()}")
                        .padding(vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(navigationLabel(item), Modifier.weight(1f).padding(horizontal = Space.md),
                            style = MaterialTheme.typography.titleMedium)
                        IconButton({ vm.toggle(item) }, enabled = state.editing && !state.busy && item != NavigationItemId.SETTINGS,
                            modifier = Modifier.testTag("navigation-visible-${item.name.lowercase()}")) {
                            val visible = item in state.draft.visible
                            Icon(if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = null, modifier = Modifier.semantics {
                                contentDescription = if (item in state.draft.visible) visibleLabel else hiddenLabel
                            })
                        }
                        Icon(Icons.Outlined.DragHandle, contentDescription = null,
                            tint = if (state.editing && !state.busy) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(48.dp)
                                .testTag("navigation-reorder-${item.name.lowercase()}").semantics {
                        contentDescription = reorder
                        customActions = listOf(
                            CustomAccessibilityAction(moveUp) {
                                if (state.editing && !state.busy && index > 0) vm.move(item, -1)
                                state.editing && !state.busy && index > 0
                            },
                            CustomAccessibilityAction(moveDown) {
                                if (state.editing && !state.busy && index < state.draft.order.lastIndex) vm.move(item, 1)
                                state.editing && !state.busy && index < state.draft.order.lastIndex
                            })
                            }.pointerInput(state.editing, state.busy, item) {
                                if (!state.editing || state.busy) return@pointerInput
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        val itemInfo = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == item.name }
                                        if (itemInfo != null) {
                                            draggedItem = item
                                            dragStartOffset = itemInfo.offset.toFloat()
                                            dragDistance = 0f
                                        }
                                    },
                                    onDragEnd = { finishDrag(commit = true) },
                                    onDragCancel = { finishDrag(commit = false) },
                                    onVerticalDrag = { change, distance ->
                                        change.consume()
                                        if (draggedItem == item) {
                                            dragDistance += distance
                                            val itemInfo = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == item.name }
                                            val from = localOrder.indexOf(item)
                                            if (itemInfo != null && from >= 0) {
                                                val draggedCenter = dragStartOffset + dragDistance + itemInfo.size / 2f
                                                val previous = localOrder.getOrNull(from - 1)?.let { previousItem ->
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == previousItem.name }
                                                }
                                                val next = localOrder.getOrNull(from + 1)?.let { nextItem ->
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == nextItem.name }
                                                }
                                                val target = when {
                                                    previous != null && draggedCenter < previous.offset + previous.size / 2f -> from - 1
                                                    next != null && draggedCenter > next.offset + next.size / 2f -> from + 1
                                                    else -> from
                                                }
                                                if (target != from) {
                                                    localOrder = localOrder.toMutableList().apply {
                                                        add(target, removeAt(from))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }.padding(12.dp))
                    }
                }
                HorizontalDivider()
            }
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
internal fun SettingEntry(title: String, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.cardInset, vertical = Space.md),
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
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        item { Text(stringResource(R.string.settings_base_currency), style = MaterialTheme.typography.titleLarge) }
        item { CurrencyChoice(base?.code.orEmpty(), { vm.selectBase(Currency.of(it)) }, !state.busy,
            Currency.supported.map { it.code to it.name }, glass = true) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(if (base == null) stringResource(R.string.settings_no_base) else
                    stringResource(R.string.settings_fx_direction, base.code))
                Text(stringResource(R.string.settings_rate_precision), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.sourceCurrency.code }) { index, row ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(0.42f)) {
                        CurrencyChoice(row.sourceCurrency.code, { vm.updateRow(index, source = Currency.of(it)) },
                            !state.busy, Currency.supported.map { it.code to it.name },
                            state.rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() +
                                listOfNotNull(base?.code), glass = true)
                    }
                    Box(Modifier.weight(0.58f)) {
                        GlassTextField(row.rateInput, { vm.updateRow(index, rate = it) },
                            stringResource(R.string.settings_rate), enabled = !state.busy, keyboardType = KeyboardType.Decimal)
                    }
                    IconButton({ vm.removeRate(index) }, enabled = !state.busy,
                        modifier = Modifier.size(48.dp).testTag("remove-rate-$index")) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_remove_rate))
                    }
                }
                HorizontalDivider(Modifier.padding(top = Space.xs))
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
internal fun languageLabel(value: AppLanguage): String = stringResource(when (value) {
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
private fun navigationLabel(value: NavigationItemId): String = stringResource(when (value) {
    NavigationItemId.ACCOUNTS -> R.string.settings_nav_accounts
    NavigationItemId.INVESTMENTS -> R.string.settings_nav_investments
    NavigationItemId.STATISTICS -> R.string.settings_nav_statistics
    NavigationItemId.SETTINGS -> R.string.settings_nav_settings
})

@Composable
internal fun settingsErrorMessage(error: ErrorCode): String = stringResource(when (error) {
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
