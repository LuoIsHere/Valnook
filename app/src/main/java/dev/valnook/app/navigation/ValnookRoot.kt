package dev.valnook.app.navigation
import androidx.compose.foundation.layout.size

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.designsystem.GlassSurface
import dev.valnook.designsystem.rememberGlassBackdrop
import dev.valnook.designsystem.glassSource
import dev.valnook.designsystem.LocalPageTopSpace
import dev.valnook.designsystem.LocalPageTitle
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.feature.settings.ClearDataScreen
import dev.valnook.feature.settings.FxSettingsScreen
import dev.valnook.feature.settings.GainLossColorsScreen
import dev.valnook.feature.settings.LanguageSettingsScreen
import dev.valnook.feature.settings.SettingsHome
import dev.valnook.feature.settings.AppThemeMode
import dev.valnook.feature.settings.SettingsViewModel
import dev.valnook.feature.settings.NavigationSettingsScreen
import dev.valnook.feature.settings.NavigationSettingsViewModel
import dev.valnook.feature.settings.NavigationToolbarState
import dev.valnook.feature.statistics.StatisticsScreen
import dev.valnook.feature.statistics.StatisticsViewModel
import dev.valnook.feature.wallet.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValnookRoot(
    sessions: AppSessionManager,
    webAdmin: dev.valnook.domain.webadmin.WebAdminService,
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeChange: suspend (AppThemeMode) -> Boolean = { false },
    onExit: () -> Unit = {}
) {
    val active by sessions.session.collectAsState()
    val cloudState by sessions.observeCloudState().collectAsStateWithLifecycle(
        dev.valnook.domain.cloud.CloudBackupRuntimeState())
    // Tagged with the session they belong to: after switching, the previous session's settings are kept until the
    // new ones arrive, and must not build its first frame.
    val sessionSettings by remember(active.id) {
        val id = active.id
        active.graph.settings.observeSettings().map<AppSettings, Pair<String, AppSettings>?> { id to it }
    }.collectAsStateWithLifecycle(null)
    val loadedSettings = sessionSettings?.takeIf { it.first == active.id }?.second ?: return
    val webState by webAdmin.state.collectAsStateWithLifecycle()
    val webScope = rememberCoroutineScope()
    LaunchedEffect(active.id, loadedSettings.language) {
        if (active.mode == DataMode.REAL) sessions.syncPlatformLanguage(active.id, loadedSettings.language)
    }
    key(active.id) {
        ValnookTheme(redGain = loadedSettings.gainLossColors == GainLossColorScheme.RED_GAIN) {
            if (webState.phase == dev.valnook.domain.webadmin.WebAdminPhase.ACTIVE) {
                dev.valnook.feature.webadmin.WebAdminLockScreen(webState.client) {
                    webScope.launch { webAdmin.stop() }
                }
            } else Box(Modifier.fillMaxSize()) {
                dev.valnook.app.onboarding.OnboardingGate(active.graph.settings, active.graph.settingsWriter, sessions.onboarding,
                    active.graph.fxSync) {
                    // The gate's AnimatedContent passes full-screen min constraints; this Box loosens them for the banner.
                    Box(Modifier.fillMaxSize()) {
                        SessionRoot(active, loadedSettings, sessions, webAdmin, themeMode, onThemeChange, onExit)
                        val fxNotice by remember(active.id) { active.graph.fxSync.observeNotice() }
                            .collectAsStateWithLifecycle(null)
                        val noticeScope = rememberCoroutineScope()
                        // Background failures stack under the status bar, above every page.
                        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()),
                            verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            CloudFailureBanner(cloudState, sessions)
                            FxNoticeBanner(fxNotice, { id -> noticeScope.launch { active.graph.fxSync.consumeNotice(id) } })
                        }
                    }
                }
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
        modifier = modifier.fillMaxWidth().padding(start = Space.md, end = Space.md).testTag("cloud-failure-banner"),
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
    webAdmin: dev.valnook.domain.webadmin.WebAdminService,
    themeMode: AppThemeMode,
    onThemeChange: suspend (AppThemeMode) -> Boolean,
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
    // The sub-account and instrument pages deepest in the stack keep their names loaded while a page sits above
    // them, so coming back shows the title at once rather than after a new query.
    val cashKey = stack.lastOrNull { it is CashKey } as CashKey?
    val instrumentKey = stack.lastOrNull { it is InstrumentKey } as InstrumentKey?
    val selectedCash by remember(graph,cashKey) {
        cashKey?.let { graph.cash.observeCashAccount(it.cashAccountId) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val selectedInstrument by remember(graph,instrumentKey) {
        instrumentKey?.let { graph.instruments.observeInstrument(it.id) } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(null)
    val accounts by remember(graph) { graph.accounts.observe_accounts() }.collectAsStateWithLifecycle(emptyList())
    val fxContext by remember(graph) { graph.overview.observeFxContext() }
        .collectAsStateWithLifecycle(dev.valnook.domain.fx.FxContext())
    val pickerRates = remember(settings, fxContext) {
        // Today's rate per currency: the current rate, else the latest stored day.
        val rates = dev.valnook.domain.fx.FxRateResolver(settings, fxContext)
        CurrencyPickerRates(settings.baseCurrency?.code, dev.valnook.domain.model.Currency.supported
            .mapNotNull { currency -> rates.display(currency.code)?.let { currency.code to it.toPlainString() } }.toMap())
    }
    val accountName: (Long) -> String = { id -> accounts.firstOrNull { it.id == id }?.name.orEmpty() }
    val scope = rememberCoroutineScope()
    val open: (NavKey) -> Unit = { route -> if (stack.last() != route) stack.add(route) }
    // A form's account choice comes back here from its page.
    val accountPicks = remember(graph.sessionId) { AccountPicks() }
    var walletToolbar by remember(graph.sessionId) { mutableStateOf<WalletToolbar?>(null) }
    val walletOverview=androidx.lifecycle.viewmodel.compose.viewModel(key="wallet-overview-${graph.sessionId}") {
        dev.valnook.feature.wallet.WalletOverviewViewModel(requireNotNull(graph.wallet))
    }
    val walletPreloadEdge=with(LocalDensity.current){
        (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp-48.dp).coerceAtMost(440.dp).roundToPx()
    }
    LaunchedEffect(walletOverview,walletPreloadEdge){
        androidx.compose.runtime.withFrameNanos { }
        walletOverview.start(walletPreloadEdge)
    }
    val appContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    val owner=androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(walletOverview,owner,walletPreloadEdge) {
        val memory=object:android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration:android.content.res.Configuration){}
            override fun onLowMemory(){walletOverview.trimMemory()}
            override fun onTrimMemory(level:Int){if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)walletOverview.trimMemory()}
        }
        val lifecycle=androidx.lifecycle.LifecycleEventObserver { _,event->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START)walletOverview.start(walletPreloadEdge)
        }
        appContext.registerComponentCallbacks(memory);owner.lifecycle.addObserver(lifecycle)
        onDispose{appContext.unregisterComponentCallbacks(memory);owner.lifecycle.removeObserver(lifecycle);walletOverview.stop()}
    }
    val updateWalletToolbar = remember(graph.sessionId) { { value: WalletToolbar? -> walletToolbar = value } }
    val selectRoot: (NavKey) -> Unit = { route ->
        if (current.isRoot() && current != route) {
            if(current == WalletKey)walletToolbar?.back?.invoke()
            stack.remove(route)
            stack.add(route)
        }
    }
    var navigationToolbar by remember(graph.sessionId) { mutableStateOf<NavigationToolbarState?>(null) }
    val updateNavigationToolbar = remember(graph.sessionId) {
        { value: NavigationToolbarState? -> navigationToolbar = value }
    }
    var fxToolbar by remember(graph.sessionId) { mutableStateOf<dev.valnook.feature.settings.FxToolbarState?>(null) }
    val updateFxToolbar = remember(graph.sessionId) {
        { value: dev.valnook.feature.settings.FxToolbarState? -> fxToolbar = value }
    }
    var leavePrompt by remember(graph.sessionId) { mutableStateOf(false) }
    val pop: () -> Unit = {
        if (stack.last() == BackupKey) scope.launch { sessions.cancelPendingRestore(active.id) }
        if (!stack.last().isRoot()) stack.removeAt(stack.lastIndex)
        else if (stack.last() != firstRoot) {
            stack.remove(firstRoot)
            stack.add(firstRoot)
        } else onExit()
    }
    // The toolbar's back and the system back both come here: a page that saves from the toolbar asks first while
    // its draft has changes. Leaving drops the page, and with it the draft.
    val back: () -> Unit = {
        val unsaved = when (stack.last()) {
            NavigationSettingsKey -> navigationToolbar?.dirty == true
            FxSettingsKey -> fxToolbar?.dirty == true
            else -> false
        }
        if (unsaved) leavePrompt = true else pop()
    }
    val walletCurrent = current == WalletKey || current == HiddenWalletKey
    val walletDetail = walletCurrent && walletToolbar?.detail == true
    val walletNavigationHidden = walletCurrent && walletToolbar?.navigationHidden == true
    val title = rootTitle(current, walletToolbar?.title, accountName,
        { id -> selectedInstrument?.takeIf { it.id == id }?.name }, { id -> selectedCash?.takeIf { it.id == id }?.name })
    val offset = with(LocalDensity.current) { 16.dp.roundToPx() }
    val barHeight = (56f + 32f * (LocalDensity.current.fontScale.coerceAtLeast(1f) - 1f)).dp
    val backdrop = rememberGlassBackdrop()
    val accountImages = remember(graph.sessionId) { dev.valnook.designsystem.AccountImageLoader(graph.overview::accountIconImage) }
    val glassTop = current.isRoot() || current == HiddenWalletKey
    val toolbarScrollState = rememberToolbarScrollState(stack)
    val toolbarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(toolbarScrollState)
    // Only whether content is under the bar reaches composition, not every pixel of a scroll.
    val scrolledUnder by remember(toolbarScrollState) { derivedStateOf { toolbarScrollState.contentOffset < -0.5f } }
    val toolbarBackgroundAlpha by animateFloatAsState(
        // The account and sub-account pages' balance moves up into the bar, so the bar stays clear there; nothing
        // scrolls under it.
        if (current !is AccountKey && current !is CashKey && current !is InstrumentKey && (if(walletCurrent)walletToolbar?.scrolled==true else scrolledUnder)) 1f else 0f,
        animationSpec = tween(120), label = "toolbar-background")
    val density = LocalDensity.current
    // Whether the keyboard is open, not its height on each frame of its animation.
    val ime = WindowInsets.ime
    val keyboardOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val showCapsule = current.isRoot() && !keyboardOpen && !walletDetail
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val systemBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val bottomSpace = if (showCapsule) with(density) { overlayHeightPx.toDp() } else systemBottom
    // The bottom navigation folds to one tab while a root page scrolls down (see NavigationCollapse). It is left
    // alone under a card's details, the keyboard or a sub-page, so it comes back as it was.
    val navigationFolded = rememberSaveable(graph.sessionId) { mutableStateOf(false) }
    val navigationCollapse = remember(graph.sessionId) {
        NavigationCollapse(navigationFolded, with(density) { 24.dp.toPx() })
    }
    val navigationPaused = !current.isRoot() || keyboardOpen || walletDetail || walletNavigationHidden
    SideEffect { navigationCollapse.paused = navigationPaused }
    // The same top the toolbar's background goes by.
    val atPageTop = if (walletCurrent) walletToolbar?.scrolled != true else !scrolledUnder
    LaunchedEffect(atPageTop, navigationPaused) { if (atPageTop) navigationCollapse.atTop() }
    // Another root page opens with the whole bar; coming back from a sub-page keeps the bar as it was.
    var lastRoot by remember(graph.sessionId) { mutableStateOf<NavKey?>(null) }
    LaunchedEffect(current) {
        if (current.isRoot() && current != lastRoot) {
            if (lastRoot != null) navigationCollapse.expand()
            lastRoot = current
        }
    }
    val navigationFold by animateFloatAsState(if (navigationCollapse.folded) 1f else 0f,
        tween(300, easing = FastOutSlowInEasing), label = "navigation-fold")
    var switching by remember { mutableStateOf(false) }
    var switchFailed by remember { mutableStateOf(false) }
    val toolbars = remember(graph.sessionId) { RootToolbars() }

    // Per page: while one page leaves and the next arrives, the leaving page's reset must not clear the new one's room.
    val titleReserves = remember(graph.sessionId) { androidx.compose.runtime.mutableStateMapOf<NavKey, Int>() }
    val updateTitleReserve: (NavKey, Int) -> Unit = remember(graph.sessionId) { { key: NavKey, value: Int ->
        if (value == 0) titleReserves.remove(key) else titleReserves[key] = value } }
    if (leavePrompt) dev.valnook.feature.settings.UnsavedChangesDialog(onKeep = { leavePrompt = false },
        onDiscard = { leavePrompt = false; pop() })
    var deleteTarget by remember(graph.sessionId) { mutableStateOf<Pair<Long, Long?>?>(null) }
    var instrumentDeleteTarget by remember(graph.sessionId) { mutableStateOf<Long?>(null) }
    instrumentDeleteTarget?.let { instrumentId ->
        dev.valnook.feature.investments.InstrumentDeletionDialog(graph.commands, instrumentId,
            onDismiss = { instrumentDeleteTarget = null }, onDeleted = {
                instrumentDeleteTarget = null
                // Leave every page about the instrument: its detail, its editor and what was opened from them.
                val first = stack.indexOfFirst { (it is InstrumentKey && it.id == instrumentId) ||
                    (it is InstrumentEditKey && it.id == instrumentId) }
                if (first > 0) while (stack.size > first) stack.removeAt(stack.lastIndex)
            })
    }
    deleteTarget?.let { (accountId, cashId) ->
        dev.valnook.feature.accounts.AccountDeletionDialog(graph.commands, accountId, cashId,
            onDismiss = { deleteTarget = null }, onDeleted = {
                deleteTarget = null
                // Drop routes/drafts referencing the removed data.
                val target: NavKey = if (cashId != null) AccountKey(accountId)
                    else if (AccountsKey in visibleRoots) AccountsKey else HiddenAccountsKey
                stack.removeAll { !it.isRoot() }
                if (target.isRoot()) stack.remove(target)
                stack.add(target)
            }, discardDraft = current is AccountEditKey)
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.nestedScroll(toolbarScrollBehavior.nestedScrollConnection)
            .nestedScroll(navigationCollapse.connection),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), topBar = {
            Box(Modifier.testTag("root-toolbar")) {
            if (toolbarBackgroundAlpha > 0f) GlassSurface(
                Modifier.matchParentSize().graphicsLayer { alpha = toolbarBackgroundAlpha }
                    .testTag("root-toolbar-background"),
                shape = RectangleShape, backdrop = if (glassTop) backdrop else null
            ) {}
            TopAppBar(title = {
                Column {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (current is AccountKey) accounts.firstOrNull { it.id == current.id }?.icon?.let { icon ->
                            CompositionLocalProvider(dev.valnook.designsystem.LocalAccountImageLoader provides accountImages) {
                                dev.valnook.designsystem.AccountAvatar(icon.symbol, icon.imageKey, size = 32.dp)
                            }
                        }
                        // Both titles stay centred while the box eases between their heights, so a one-line title
                        // replacing a two-line one neither starts high nor drops once the old one has gone.
                        if(walletCurrent) androidx.compose.animation.AnimatedContent(title,Modifier.weight(1f).testTag("wallet-title"),
                            transitionSpec={ (androidx.compose.animation.fadeIn(tween(160)) togetherWith androidx.compose.animation.fadeOut(tween(160)))
                                .using(androidx.compose.animation.SizeTransform(clip=false){ _, _ -> tween(160) }) },
                            contentAlignment=androidx.compose.ui.Alignment.CenterStart,label="wallet-title") { value ->
                            Text(value,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        } else Text(title, Modifier.weight(1f).padding(end =
                            with(density) { (titleReserves[current] ?: 0).toDp() }), maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (active.mode == DataMode.DEMO) Text(
                        stringResource(dev.valnook.feature.settings.R.string.settings_demo_banner),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent), expandedHeight = barHeight, navigationIcon = {
                if (walletDetail) BackButton(stringResource(R.string.nav_back), { walletToolbar?.back?.invoke() })
                else if (!current.isRoot()) BackButton(stringResource(R.string.nav_back), back)
            }, actions = {
                RootTopBarActions(current, walletDetail, walletToolbar, navigationToolbar, fxToolbar, toolbars, open,
                    onDeleteAccount = { account, cash -> deleteTarget = account to cash },
                    onDeleteInstrument = { instrumentDeleteTarget = it })
            }, scrollBehavior = toolbarScrollBehavior)
            }
        }) { padding ->
            CompositionLocalProvider(dev.valnook.designsystem.LocalAccountImageLoader provides accountImages,
                LocalPageBottomSpace provides bottomSpace,
                LocalPageTopSpace provides if (glassTop) padding.calculateTopPadding() else 0.dp,
                dev.valnook.designsystem.LocalPageTopBarHeight provides barHeight,
                LocalPageTitle provides title,
                LocalCurrencyPickerRates provides pickerRates) {
                val direction = LocalLayoutDirection.current
                val viewportPadding = if (glassTop) PaddingValues(
                    start = padding.calculateLeftPadding(direction), end = padding.calculateRightPadding(direction)) else padding
                // Edge-to-edge windows are not resized for the keyboard; keep every page viewport above it.
                Box(Modifier.fillMaxSize().padding(viewportPadding).consumeWindowInsets(padding).imePadding()
                    .then(if (glassTop) Modifier.glassSource(backdrop) else Modifier)
                    .background(MaterialTheme.colorScheme.background).testTag("page-viewport")) {
                    NavDisplay(backStack = stack, onBack = back, sizeTransform = null,
                        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator()),
                        transitionSpec = { NavigationMotion.forward(offset) },
                        popTransitionSpec = { NavigationMotion.back(offset) },
                        predictivePopTransitionSpec = { NavigationMotion.no_preview() }, entryProvider = entryProvider {
                            accountEntries(graph, open, back, toolbars.onEditSort, toolbars.onAccountEdit, toolbars.onAccountGroups,
                                updateTitleReserve) { parent, cash ->
                                deleteTarget = parent to cash
                            }
                            investmentEntries(graph, open, back, accountName, updateTitleReserve, accountPicks, toolbars.onType, toolbars.onInstrument,
                                toolbars.onDividend)
                            ledgerEntries(graph, open, back, accountName, toolbars.onBalance, updateTitleReserve, toolbars.onDeposit)
                            // A page left off the bar opens from settings as its hidden key: the same page.
                            val walletPage = @Composable {
                                WalletScreen(pageViewModel { WalletViewModel(graph.sessionId, requireNotNull(graph.wallet),
                                    graph.overview, graph.cashPages, graph.clock, createSavedStateHandle(),walletOverview.cards,graph.walletPrivate,graph.cash) }, updateWalletToolbar,
                                    { parent, cash, id -> open(CashEntryKey(parent,cash,id)) },
                                    { parent, cash -> open(CashBalanceEditKey(parent,cash)) },walletCashSlots,walletOverview.scroll,
                                    maxOf(systemBottom,with(density){overlayHeightPx.toDp()}),walletOverview.cache)
                            }
                            entry<WalletKey> { walletPage() }
                            val billsPage = @Composable {
                                dev.valnook.feature.bills.BillsScreen(pageViewModel {
                                    dev.valnook.feature.bills.BillsViewModel(requireNotNull(graph.bills),
                                        graph.settings.observeSettings(), graph.overview.observeFxContext(), graph.clock,
                                        createSavedStateHandle())
                                }, { open(BillDetailKey(it)) }, { open(BillEditorKey(copyFromId = it)) }, toolbars.onBills)
                            }
                            entry<BillsKey> { billsPage() }
                            entry<HiddenBillsKey> { billsPage() }
                            entry<BillEditorKey> { route ->
                                dev.valnook.feature.bills.BillEditorScreen(pageViewModel {
                                    dev.valnook.feature.bills.BillEditorViewModel(route.billId, route.copyFromId,
                                        dev.valnook.feature.bills.BillTab.valueOf(route.tab), requireNotNull(graph.bills),
                                        graph.commands, graph.settings.observeSettings(), graph.overview.observeFxContext(),
                                        graph.clock, createSavedStateHandle())
                                }, onDone = pop, onManageCategories = { open(BillCategoriesKey) })
                            }
                            entry<BillDetailKey> { route ->
                                dev.valnook.feature.bills.BillDetailScreen(pageViewModel {
                                    dev.valnook.feature.bills.BillDetailViewModel(route.id, requireNotNull(graph.bills), graph.commands)
                                }, { open(BillEditorKey(billId = it)) }, { open(BillEditorKey(copyFromId = it)) }, onDeleted = pop,
                                    onOpen = { open(BillDetailKey(it)) }, onSettle = { id, type -> open(BillSettleKey(id, type.name)) })
                            }
                            entry<ReimbursementsKey> {
                                dev.valnook.feature.bills.ReimbursementsScreen(pageViewModel {
                                    dev.valnook.feature.bills.ReimbursementsViewModel(requireNotNull(graph.bills), graph.commands,
                                        graph.settings.observeSettings(), graph.overview.observeFxContext(), graph.clock,
                                        createSavedStateHandle())
                                }, onOpen = { open(BillDetailKey(it)) },
                                    onReimburse = { open(BillSettleKey(it, dev.valnook.domain.model.BillType.REIMBURSEMENT.name)) })
                            }
                            entry<BillSettleKey> { route ->
                                dev.valnook.feature.bills.BillSettleScreen(pageViewModel {
                                    dev.valnook.feature.bills.BillSettleViewModel(route.parentId,
                                        dev.valnook.domain.model.BillType.valueOf(route.type), requireNotNull(graph.bills),
                                        graph.commands, graph.settings.observeSettings(), graph.overview.observeFxContext(),
                                        graph.clock, createSavedStateHandle())
                                }, onDone = pop)
                            }
                            entry<BillCategoriesKey> {
                                dev.valnook.feature.bills.BillCategoriesScreen(pageViewModel {
                                    dev.valnook.feature.bills.BillCategoriesViewModel(requireNotNull(graph.bills), graph.commands,
                                        createSavedStateHandle())
                                }, toolbars.onBillCategories)
                            }
                            entry<HiddenWalletKey> { walletPage() }
                            val statisticsPage = @Composable {
                                StatisticsScreen(pageViewModel {
                                    StatisticsViewModel(graph.statistics, graph.clock, createSavedStateHandle())
                                }, summary = {
                                    dev.valnook.feature.accounts.AccountOverviewSummary(pageViewModel {
                                        dev.valnook.feature.accounts.AccountsViewModel(graph.overview)
                                    })
                                })
                            }
                            entry<StatisticsKey> { statisticsPage() }
                            entry<HiddenStatisticsKey> { statisticsPage() }
                            entry<SettingsKey> {
                                val vm = pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle(), graph.fxSync)
                                }
                                val bills = graph.bills
                                val reimbursements by remember(bills) {
                                    bills?.let { dev.valnook.feature.bills.ReimbursementsViewModel.observeSummary(it,
                                        graph.settings.observeSettings(), graph.overview.observeFxContext()) }
                                        ?: kotlinx.coroutines.flow.flowOf(null)
                                }.collectAsStateWithLifecycle(null)
                                val reimbursementText = dev.valnook.feature.bills.reimbursementSummaryText(reimbursements)
                                SettingsHome(vm, active.mode == DataMode.DEMO, switching, switchFailed,
                                    { open(FxSettingsKey) }, { enable ->
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
                                    { open(WebAdminKey) },
                                    { open(it.hiddenKey()) }, BuildConfig.VERSION_NAME, BuildConfig.INTERNAL_BUILD_ID,
                                    BuildConfig.VERSION_CODE, themeMode, onThemeChange, onAbout = { open(AboutKey) },
                                    onCardFaces = { open(WalletFaceLibraryKey) },
                                    onReimbursements = if (bills != null) ({ open(ReimbursementsKey) }) else null,
                                    reimbursementSummary = reimbursementText)
                            }
                            entry<WalletFaceLibraryKey> {
                                dev.valnook.feature.wallet.WalletFaceLibraryScreen(pageViewModel {
                                    dev.valnook.feature.wallet.WalletFaceLibraryViewModel(requireNotNull(graph.wallet))
                                }, walletOverview.cache, toolbars.onWalletFaces)
                            }
                            entry<AboutKey> {
                                dev.valnook.feature.settings.AboutScreen(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                                    { open(PrivacyPolicyKey) },
                                    { open(LicenseKey) }, BuildConfig.INTERNAL_BUILD_ID, { open(ThirdPartyKey) })
                            }
                            entry<LicenseKey> { dev.valnook.feature.settings.LicenseScreen() }
                            entry<ThirdPartyKey> { dev.valnook.feature.settings.ThirdPartyNoticesScreen() }
                            entry<PrivacyPolicyKey> {
                                dev.valnook.feature.settings.PrivacyPolicyScreen()
                            }
                            entry<BackupKey> {
                                dev.valnook.feature.backup.BackupScreen(pageViewModel {
                                    dev.valnook.feature.backup.BackupViewModel(
                                        graph.portability, graph.settings, active.mode == DataMode.DEMO,
                                        graph.cloudBackup, sessions.cloudAuthorization)
                                }, active.mode == DataMode.DEMO)
                            }
                            entry<WebAdminKey> {
                                dev.valnook.feature.webadmin.WebAdminScreen(pageViewModel {
                                    dev.valnook.feature.webadmin.WebAdminViewModel(webAdmin)
                                }, active.mode == DataMode.DEMO)
                            }
                            entry<NavigationSettingsKey> {
                                NavigationSettingsScreen(pageViewModel {
                                    NavigationSettingsViewModel(graph.settings, graph.settingsWriter,
                                        createSavedStateHandle())
                                }, onToolbarChange = updateNavigationToolbar)
                            }
                            entry<FxSettingsKey> {
                                FxSettingsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle(), graph.fxSync)
                                }, onToolbarChange = updateFxToolbar)
                            }
                            entry<LanguageSettingsKey> {
                                LanguageSettingsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                }, onClose = back)
                            }
                            entry<GainLossColorsKey> {
                                GainLossColorsScreen(pageViewModel {
                                    SettingsViewModel(graph.settings, graph.settingsWriter, createSavedStateHandle())
                                }, onClose = back)
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
        // Sub-pages and the keyboard still remove the bar at once; only wallet card details animate it.
        if (current.isRoot() && !keyboardOpen) AnimatedVisibility(!walletNavigationHidden,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = NavigationMotion.bar_enter(), exit = NavigationMotion.bar_exit()) {
            // Taps wait until the card has returned, like the wallet toolbar actions.
            FloatingNavigationBar(current, settings.navigation.visibleInOrder, { if (!walletDetail) selectRoot(it) },
                Modifier.fillMaxWidth().onSizeChanged { overlayHeightPx = it.height }
                    .testTag("floating-navigation-overlay"), backdrop,
                fold = { navigationFold }, onExpand = navigationCollapse::expand,
                onAddBill = if (graph.bills != null && !walletDetail) ({ open(BillEditorKey()) }) else null)
        }
    }
}
