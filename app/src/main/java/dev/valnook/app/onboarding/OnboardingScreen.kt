package dev.valnook.app.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.valnook.app.R
import dev.valnook.designsystem.*
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.Currency
import dev.valnook.domain.repository.SettingsRepository
import dev.valnook.domain.repository.SettingsWriter
import dev.valnook.feature.settings.*

@Composable
fun OnboardingGate(settings: SettingsRepository, writer: SettingsWriter, storage: OnboardingStorage,
    content: @Composable () -> Unit) {
    // The caller keys this composition by session, so data deletion cannot retain a completed gate.
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel(viewModelStoreOwner = owner) { OnboardingViewModel(storage, settings, writer) }
    OnboardingFlow(vm, content)
}

@Composable
internal fun OnboardingFlow(vm: OnboardingViewModel, content: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loadFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.onboarding_save_error))
                TextButton({ vm.reload() }) { Text(stringResource(R.string.onboarding_retry)) }
            } else CircularProgressIndicator()
        }
        return
    }
    var privacy by rememberSaveable { mutableStateOf(false) }
    val backLabel = stringResource(R.string.onboarding_back)
    BackHandler(privacy || state.draft.step == OnboardingStep.FINANCE) {
        if (privacy) privacy = false else if (!state.busy) vm.back()
    }
    if (privacy) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(backLabel) { privacy = false }
                Text(stringResource(dev.valnook.feature.settings.R.string.settings_privacy_policy),
                    style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f)) { PrivacyPolicyScreen() }
        }
        return
    }
    AnimatedContent(state.draft.step, modifier = Modifier.fillMaxSize(), transitionSpec = {
        val direction = if (targetState == OnboardingStep.WELCOME) 1 else -1
        (slideInHorizontally(tween(260)) { -direction * it } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(260)) { direction * it } + fadeOut(tween(160)))
    }, label = "onboarding-step") { step ->
        when (step) {
            OnboardingStep.COMPLETE, OnboardingStep.LEGACY -> content()
            OnboardingStep.WELCOME -> WelcomeScreen(state, vm, { privacy = true })
            OnboardingStep.FINANCE -> FinanceScreen(state, vm)
            OnboardingStep.READY -> ReadyScreen(state, vm::complete)
        }
    }
}

@Composable
private fun ReadyScreen(state: OnboardingState, onStart: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val pageHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = pageHeight)
            .padding(Space.xl), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.padding(top = (pageHeight * .16f).coerceAtMost(120.dp)),
                verticalArrangement = Arrangement.spacedBy(Space.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(76.dp)) {
                    drawLine(color, Offset(size.width * .18f, size.height * .52f),
                        Offset(size.width * .42f, size.height * .76f), 5.dp.toPx(), StrokeCap.Round)
                    drawLine(color, Offset(size.width * .42f, size.height * .76f),
                        Offset(size.width * .84f, size.height * .26f), 5.dp.toPx(), StrokeCap.Round)
                }
                Text(stringResource(R.string.onboarding_ready), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.onboarding_demo_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Column {
                state.error?.let { Text(stringResource(R.string.onboarding_save_error), color = MaterialTheme.colorScheme.error) }
                Button(onStart, Modifier.fillMaxWidth().testTag("onboarding-enter"),
                    enabled = !state.busy, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.onboarding_start))
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(state: OnboardingState, vm: OnboardingViewModel, onPrivacy: () -> Unit) {
    var languagePicker by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(state.draft.introSeen) }
    LaunchedEffect(Unit) { started = true; vm.introSeen() }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val pageHeight = maxHeight
        val logoOffset by animateDpAsState(if (started) 0.dp else pageHeight / 2 - 110.dp,
            tween(900, easing = FastOutSlowInEasing), label = "welcome-logo")
        val nameAlpha by animateFloatAsState(if (started) 1f else 0f, tween(320, delayMillis = 550), label = "welcome-name")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = pageHeight)
            .padding(horizontal = Space.xl, vertical = Space.md), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height((pageHeight * .5f).coerceAtLeast(240.dp)), contentAlignment = Alignment.TopCenter) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(220.dp).offset(y = logoOffset).testTag("onboarding-logo"))
                Text("Valnook", Modifier.padding(top = 196.dp).graphicsLayer { alpha = nameAlpha },
                    style = MaterialTheme.typography.headlineMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                SelectorField(stringResource(dev.valnook.feature.settings.R.string.settings_language),
                    languageLabel(state.language), { languagePicker = true }, !state.busy, glass = true)
                state.error?.let { Text(stringResource(R.string.onboarding_save_error), color = MaterialTheme.colorScheme.error) }
                Button(vm::start, Modifier.fillMaxWidth().testTag("onboarding-start"),
                    enabled = state.draft.accepted && !state.busy, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.onboarding_start))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val consentLabel = stringResource(R.string.onboarding_agree) +
                        stringResource(dev.valnook.feature.settings.R.string.settings_privacy_policy)
                    Checkbox(state.draft.accepted, vm::accept, enabled = !state.busy,
                        modifier = Modifier.testTag("onboarding-consent").semantics { contentDescription = consentLabel })
                    Text(buildAnnotatedString {
                        append(stringResource(R.string.onboarding_agree))
                        withLink(LinkAnnotation.Clickable("privacy", TextLinkStyles(SpanStyle(
                            color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))) {
                            onPrivacy()
                        }) { append(stringResource(dev.valnook.feature.settings.R.string.settings_privacy_policy)) }
                    }, Modifier.weight(1f).testTag("onboarding-privacy"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    GlassChoiceDialog(languagePicker, stringResource(dev.valnook.feature.settings.R.string.settings_language),
        AppLanguage.entries, state.language, { languageLabel(it) }, onSelect = {
            languagePicker = false
            vm.language(it)
        }, onDismiss = { languagePicker = false }, tag = "onboarding-language",
        optionTag = { "onboarding-language-${it.name}" }, busy = state.busy)
}

@Composable
private fun FinanceScreen(state: OnboardingState, vm: OnboardingViewModel) {
    var pendingBase by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val rates = CurrencyPickerRates(state.draft.base?.code,
        state.draft.rates.associate { it.sourceCurrency.code to it.rateInput })
    CompositionLocalProvider(LocalCurrencyPickerRates provides rates) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(end = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                BackButton(stringResource(R.string.onboarding_back)) { if (!state.busy) vm.back() }
                Text(stringResource(R.string.onboarding_finance), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Text("2 / 2", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(stringResource(R.string.onboarding_finance_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FxRateEditor(state.draft.base, state.draft.rates, state.busy, onBase = {
                    if (it != state.draft.base) {
                        if (state.draft.rates.isNotEmpty()) pendingBase = it.code else vm.base(it)
                    }
                }, onAdd = vm::addRate, onUpdate = vm::updateRate, onRemove = vm::removeRate)
                state.error?.let {
                    Text(settingsErrorMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("onboarding-error"))
                }
            }
            Button({ focus.clearFocus(); vm.finish() },
                Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.md).testTag("onboarding-finish"),
                enabled = state.draft.base != null && !state.busy, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(if (state.busy) R.string.onboarding_saving else R.string.onboarding_finish))
            }
        }
    }
    AnimatedGlassDialog(pendingBase != null, { pendingBase = null }) {
        GlassCard(prominent = true) {
            Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(stringResource(R.string.onboarding_change_base), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.onboarding_change_base_hint))
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ pendingBase = null }) { Text(stringResource(R.string.onboarding_cancel)) }
                    TextButton({ pendingBase?.let { vm.base(Currency.of(it)) }; pendingBase = null }) {
                        Text(stringResource(R.string.onboarding_confirm))
                    }
                }
            }
        }
    }
}
