package dev.valnook.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import dev.valnook.app.R
import dev.valnook.app.BuildConfig
import dev.valnook.app.di.ActiveSession
import dev.valnook.app.di.AppGraph
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.DataMode
import dev.valnook.designsystem.BackButton
import dev.valnook.designsystem.CurrencyPickerRates
import dev.valnook.designsystem.LocalCurrencyPickerRates
import dev.valnook.designsystem.LocalPageBottomSpace
import dev.valnook.designsystem.MenuIcon
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.feature.settings.ClearDataScreen
import dev.valnook.feature.settings.FxSettingsScreen
import dev.valnook.feature.settings.GainLossColorsScreen
import dev.valnook.feature.settings.LanguageSettingsScreen
import dev.valnook.feature.settings.SettingsHome
import dev.valnook.feature.settings.SettingsViewModel
import dev.valnook.feature.settings.NavigationSettingsScreen
import dev.valnook.feature.settings.NavigationSettingsViewModel
import dev.valnook.feature.statistics.StatisticsScreen
import dev.valnook.feature.statistics.StatisticsViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValnookRoot(sessions: AppSessionManager, onExit: () -> Unit = {}) {
    val active by sessions.session.collectAsState()
    val cloudState by sessions.observeCloudState().collectAsStateWithLifecycle(
        dev.valnook.domain.cloud.CloudBackupRuntimeState())
    val settings by remember(active.id) {
        active.graph.settings.observeSettings().map<AppSettings, AppSettings?> { it }
    }.collectAsStateWithLifecycle(null)
    val loadedSettings = settings ?: return
    LaunchedEffect(active.id, loadedSettings.language) {
        if (active.mode == DataMode.REAL) sessions.syncPlatformLanguage(active.id, loadedSettings.language)
    }
    key(active.id) {
        ValnookTheme(redGain = loadedSettings.gainLossColors == GainLossColorScheme.RED_GAIN) {
            Box(Modifier.fillMaxSize()) {
                SessionRoot(active, loadedSettings, sessions, onExit)
                CloudFailureBanner(cloudState, sessions, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun CloudFailureBanner(
    state: dev.valnook.domain.cloud.CloudBackupRuntimeState,
    sessions: AppSessionManager,
    modifier: Modifier = Modifier
) {
    val eventId = state.pendingBannerEventId ?: return
    val accessibility = LocalAccessibilityManager.current
    var visible by remember(eventId) { mutableStateOf(true) }
    LaunchedEffect(eventId) {
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = 3_000,
            containsIcons = false,
            containsText = true,
            containsControls = false
        ) ?: 3_000L
        delay(timeout)
        visible = false
        sessions.consumeCloudBanner(eventId)
    }
    if (visible) Surface(
        modifier = modifier.fillMaxWidth().padding(
            top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
            start = Space.md, end = Space.md),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
            Text(stringResource(R.string.cloud_failure_title), style = MaterialTheme.typography.titleSmall)
            Text(cloudBannerReason(state.pendingBannerError), style = MaterialTheme.typography.bodySmall,
                maxLines = 2)
        }
    }
}

@Composable
private fun cloudBannerReason(error: dev.valnook.domain.cloud.CloudBackupError?): String = when (error) {
    dev.valnook.domain.cloud.CloudBackupError.UPLOAD_UNKNOWN -> stringResource(R.string.cloud_failure_unknown)
    dev.valnook.domain.cloud.CloudBackupError.AUTH_REQUIRED -> stringResource(R.string.cloud_failure_auth)
    dev.valnook.domain.cloud.CloudBackupError.VERIFY_FAILED -> stringResource(R.string.cloud_failure_verify)
    dev.valnook.domain.cloud.CloudBackupError.LOCAL_PREPARATION_FAILED,
    dev.valnook.domain.cloud.CloudBackupError.LOW_STORAGE -> stringResource(R.string.cloud_failure_prepare)
    else -> stringResource(R.string.cloud_failure_upload)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRoot(
    active: ActiveSession,
    settings: AppSettings,
    sessions: AppSessionManager,
    onExit: () -> Unit
) {
    val graph = active.graph
    val visibleRoots = settings.navigation.visibleInOrder.map { it.rootKey() }
    val firstRoot = visibleRoots.firstOrNull() ?: SettingsKey
    val initialRoots = remember {
        visibleRoots.filter { it != firstRoot } + firstRoot
    }
    val stack = rememberNavBackStack(*initialRoots.toTypedArray())
    val current = stack.last()
    LaunchedEffect(settings.navigation) {
        val invalidCurrentRoot = stack.last().isRoot() && stack.last() !in visibleRoots
        stack.toList().filter { it.isRoot() && it !in visibleRoots }.forEach { stack.remove(it) }
        visibleRoots.filter { it !in stack }.forEach { stack.add(0, it) }
        if (invalidCurrentRoot) {
            stack.remove(firstRoot)
            stack.add(firstRoot)
        }
    }
    val accounts by remember(graph) { graph.accounts.observe_accounts() }.collectAsStateWithLifecycle(emptyList())
    val pickerRates = remember(settings) {
        val base = settings.baseCurrency?.code
        CurrencyPickerRates(base, settings.rates.filter { it.targetCurrency.code == base }
            .associate { it.sourceCurrency.code to it.rate.stripTrailingZeros().toPlainString() })
    }
    val accountName: (Long) -> String = { id -> accounts.firstOrNull { it.id == id }?.name.orEmpty() }
    val scope = rememberCoroutineScope()
    val open: (NavKey) -> Unit = { route -> if (stack.last() != route) stack.add(route) }
    val selectRoot: (NavKey) -> Unit = { route ->
        if (current.isRoot() && current != route) {
            stack.remove(route)
            stack.add(route)
        }
    }
    val back: () -> Unit = {
        if (stack.last() == BackupKey) scope.launch { sessions.cancelPendingRestore(active.id) }
        if (!stack.last().isRoot()) stack.removeAt(stack.lastIndex)
        else if (stack.last() != firstRoot) {
            stack.remove(firstRoot)
            stack.add(firstRoot)
        } else onExit()
    }
    val title = when (current) {
        AccountsKey -> stringResource(R.string.nav_accounts)
        InvestmentsKey -> stringResource(R.string.nav_investments)
        StatisticsKey -> stringResource(R.string.nav_statistics)
        SettingsKey -> stringResource(R.string.nav_settings)
        HiddenAccountsKey -> stringResource(R.string.nav_accounts)
        HiddenInvestmentsKey -> stringResource(R.string.nav_investments)
        HiddenStatisticsKey -> stringResource(R.string.nav_statistics)
        NavigationSettingsKey -> stringResource(R.string.title_navigation_settings)
        FxSettingsKey -> stringResource(R.string.title_exchange_rates)
        LanguageSettingsKey -> stringResource(R.string.title_language)
        GainLossColorsKey -> stringResource(R.string.title_gain_loss_colors)
        ClearDataKey -> stringResource(R.string.title_clear_data)
        BackupKey -> stringResource(dev.valnook.feature.backup.R.string.backup_title)
        is AccountKey -> accountName(current.id)
        is AccountEditKey -> stringResource(R.string.title_edit_account)
        is InstrumentLibraryKey -> stringResource(R.string.title_instrument_library)
        is AccountInvestmentsKey -> stringResource(R.string.title_all_instruments)
        is InstrumentKey -> stringResource(R.string.title_instrument_detail)
        is InstrumentPriceEditKey -> stringResource(dev.valnook.feature.investments.R.string.instrument_price_edit_title)
        is AccountInstrumentKey -> accountName(current.accountId)
        is PositionCreateKey -> stringResource(dev.valnook.feature.investments.R.string.investment_add_to_account)
        is CashBalanceEditKey -> stringResource(R.string.title_edit_balance)
        else -> "Valnook"
    }
    val offset = with(LocalDensity.current) { 16.dp.roundToPx() }
    val barHeight = (64f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val showCapsule = current.isRoot() && !keyboardOpen
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val systemBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val bottomSpace = if (showCapsule) with(density) { overlayHeightPx.toDp() } else systemBottom
    var switching by remember { mutableStateOf(false) }
    var switchFailed by remember { mutableStateOf(false) }
    val addAccountDescription = stringResource(R.string.nav_add_account)
    val instrumentLibraryDescription = stringResource(R.string.nav_instrument_library)

    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), topBar = {
            TopAppBar(title = {
                Column {
                    Text(title, maxLines = 2)
                    if (active.mode == DataMode.DEMO) Text(
                        stringResource(dev.valnook.feature.settings.R.string.settings_demo_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }, expandedHeight = barHeight, navigationIcon = {
                if (!current.isRoot()) BackButton(stringResource(R.string.nav_back), back)
            }, actions = {
                when (current) {
                    AccountsKey, HiddenAccountsKey -> IconButton({ open(AccountEditKey()) }, Modifier.semantics {
                        contentDescription = addAccountDescription
                    }) { Text("＋") }
                    InvestmentsKey, HiddenInvestmentsKey -> IconButton({ open(InstrumentLibraryKey) }, Modifier.semantics {
                        contentDescription = instrumentLibraryDescription
                    }) { MenuIcon() }
                    is AccountKey -> dev.valnook.designsystem.TopBarAction(stringResource(R.string.nav_edit),
                        { open(AccountEditKey(current.id)) })
                    else -> Unit
                }
            })
        }) { padding ->
            CompositionLocalProvider(LocalPageBottomSpace provides bottomSpace,
                LocalCurrencyPickerRates provides pickerRates) {
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("page-viewport")) {
                    NavDisplay(backStack = stack, onBack = back, sizeTransform = null,
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator()),
                        transitionSpec = { NavigationMotion.forward(offset) },
                        popTransitionSpec = { NavigationMotion.back(offset) },
                        predictivePopTransitionSpec = { NavigationMotion.no_preview() }, entryProvider = entryProvider {
                            accountEntries(graph, open, back)
                            investmentEntries(graph, open, back, accountName)
                            ledgerEntries(graph, open, back, accountName)
                            entry<StatisticsKey> {
                                StatisticsScreen(pageViewModel {
                                    StatisticsViewModel(graph.statistics, graph.clock, createSavedStateHandle())
                                })
                            }
                            entry<HiddenStatisticsKey> {
                                StatisticsScreen(pageViewModel {
                                    StatisticsViewModel(graph.statistics, graph.clock, createSavedStateHandle())
                                })
                            }
                            entry<SettingsKey> {
                                val vm = pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                }
                                SettingsHome(vm, active.mode == DataMode.DEMO, switching, switchFailed,
                                    { open(FxSettingsKey) }, { if (active.mode != DataMode.DEMO) open(LanguageSettingsKey) },
                                    { open(GainLossColorsKey) }, { enable ->
                                        if (!switching) scope.launch {
                                            switching = true
                                            switchFailed = false
                                            runCatching { if (enable) sessions.enterDemo() else sessions.exitDemo() }
                                                .onFailure {
                                                    switching = false
                                                    switchFailed = true
                                                }
                                        }
                                    }, { open(ClearDataKey) }, { open(NavigationSettingsKey) }, { open(BackupKey) },
                                    { open(it.hiddenKey()) }, BuildConfig.VERSION_NAME, BuildConfig.INTERNAL_BUILD_ID)
                            }
                            entry<BackupKey> {
                                dev.valnook.feature.backup.BackupScreen(pageViewModel {
                                    dev.valnook.feature.backup.BackupViewModel(
                                        graph.portability, graph.settings, active.mode == DataMode.DEMO,
                                        graph.cloudBackup, sessions.googleAuthorization)
                                }, active.mode == DataMode.DEMO)
                            }
                            entry<NavigationSettingsKey> {
                                NavigationSettingsScreen(pageViewModel {
                                    NavigationSettingsViewModel(graph.settings, graph.settingsWriter,
                                        createSavedStateHandle())
                                })
                            }
                            entry<FxSettingsKey> {
                                FxSettingsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                })
                            }
                            entry<LanguageSettingsKey> {
                                LanguageSettingsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                })
                            }
                            entry<GainLossColorsKey> {
                                GainLossColorsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                })
                            }
                            entry<ClearDataKey> {
                                var challenge by remember(active.id) { mutableStateOf<String?>(null) }
                                var unavailable by remember(active.id) { mutableStateOf(false) }
                                LaunchedEffect(active.id) {
                                    runCatching { sessions.issueClearChallenge() }
                                        .onSuccess { challenge = it }
                                        .onFailure { unavailable = true }
                                }
                                challenge?.let { value ->
                                    ClearDataScreen(value, { input -> sessions.clearRealData(value, input) },
                                        { scope.launch { sessions.cancelClearChallenge(value) } }, back)
                                }
                                if(unavailable) Text(stringResource(dev.valnook.feature.settings.R.string.settings_clear_failed),
                                    color=MaterialTheme.colorScheme.error)
                            }
                        })
                }
            }
        }
        if (showCapsule) FloatingNavigationBar(current, settings.navigation.visibleInOrder, selectRoot,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { overlayHeightPx = it.height }
                .testTag("floating-navigation-overlay"))
    }
}
