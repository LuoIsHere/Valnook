package dev.valnook.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import dagger.hilt.android.AndroidEntryPoint
import dev.valnook.app.appearance.ThemePreferences
import dev.valnook.app.appearance.configurationOverride
import dev.valnook.app.di.AppSessionManager
import dev.valnook.designsystem.BackButton
import dev.valnook.designsystem.LocalPageBottomSpace
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.ai.AiFeatures
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.feature.ai.AiShareScreen
import dev.valnook.feature.ai.AiShareViewModel
import dev.valnook.feature.bills.BillEditorScreen
import dev.valnook.feature.bills.BillEditorViewModel
import dev.valnook.feature.bills.BillImageCodec
import dev.valnook.feature.bills.BillTab
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Valnook bill" in the system share sheet: text, or up to four pictures. It runs in the sharing app's task, so
 * closing it goes back there. With AI on for what was shared it reads it into drafts; otherwise the bill page opens
 * with the text in the note and the pictures on the bill.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var sessions: AppSessionManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        ThemePreferences(newBase).read().configurationOverride()?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        val shared = SharedContent.of(intent)
        if (shared.text.isEmpty() && shared.pictures.isEmpty()) {
            Toast.makeText(this, dev.valnook.feature.ai.R.string.ai_share_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent { ShareRoot(sessions, shared) { saved -> close(saved) } }
    }

    private fun close(saved: Int) {
        if (saved > 0) Toast.makeText(this, resources.getQuantityString(dev.valnook.feature.ai.R.plurals.ai_share_saved, saved, saved),
            Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** What another app shared: its words (subject and text) and the pictures, at most four. */
internal data class SharedContent(val text: String, val pictures: List<Uri>) {
    companion object {
        fun of(intent: Intent?): SharedContent {
            if (intent == null) return SharedContent("", emptyList())
            val words = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
            val pictures = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> emptyList()
            }.filter { uri -> intent.type?.startsWith("image/") == true || uri.scheme == "content" }
                .take(dev.valnook.domain.ai.AiRules.MAX_IMAGES)
            return SharedContent(words, pictures)
        }
    }
}

/** A bill page opened from the share: a draft's form under its operation, or the share as it came. */
private data class EditorRequest(val key: Int, val prefill: String, val operation: String?, val manual: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareRoot(sessions: AppSessionManager, shared: SharedContent, onClose: (saved: Int) -> Unit) {
    val active by sessions.session.collectAsStateWithLifecycle()
    val graph = active.graph
    val settings by graph.settings.observeSettings().collectAsStateWithLifecycle(null)
    val features by remember(graph.ai) { graph.ai?.observeFeatures() ?: flowOf(AiFeatures()) }.collectAsStateWithLifecycle(null)
    val connection by remember(graph.ai) { graph.ai?.observeSettings() ?: flowOf(null) }.collectAsStateWithLifecycle(null)
    val bills = graph.bills
    ValnookTheme(redGain = settings?.gainLossColors == GainLossColorScheme.RED_GAIN) {
        val loaded = features
        if (bills == null || loaded == null) { Surface(Modifier.fillMaxSize()) {}; return@ValnookTheme }
        val context = LocalContext.current
        val resolver = context.contentResolver
        val saver = remember(graph.sessionId) {
            dev.valnook.feature.ai.AiDraftSaver(bills, graph.commands, graph.settings.observeSettings(),
                graph.overview.observeFxContext(), graph.clock, graph.billImages)
        }
        // Decided once, when the page opens: AI for this kind of share, or straight to the bill page.
        val useAi = if (shared.pictures.isNotEmpty()) loaded.enabled && loaded.shareImage else loaded.enabled && loaded.shareText
        val vm: AiShareViewModel = viewModel(key = "share", factory = viewModelFactory { initializer {
            AiShareViewModel(graph.assistant, useAi, connection?.connection?.host.orEmpty(), shared.text,
                shared.pictures.map { uri -> suspend { BillImageCodec.prepare(resolver, uri) } }, bills, saver,
                graph.billImages, graph.clock)
        } })
        var editor by remember { mutableStateOf<EditorRequest?>(null) }
        var nextKey by rememberSaveable { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        fun openManual() {
            scope.launch { editor = EditorRequest(++nextKey, vm.manualPrefill(), null, manual = true) }
        }
        val request = editor
        BackHandler { if (request != null && !request.manual) { editor = null; vm.refresh() } else onClose(vm.state.value.saved) }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(if (request != null) dev.valnook.feature.ai.R.string.ai_share_new_bill
                else dev.valnook.feature.ai.R.string.ai_share_title)) }, navigationIcon = {
                BackButton(stringResource(dev.valnook.feature.ai.R.string.ai_share_back)) {
                    if (request != null && !request.manual) { editor = null; vm.refresh() } else onClose(vm.state.value.saved)
                }
            })
        }) { padding ->
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            CompositionLocalProvider(dev.valnook.feature.bills.LocalBillImages provides graph.billImages,
                LocalPageBottomSpace provides bottom) {
                Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).imePadding()
                    .testTag("share-root")) {
                    if (request != null) {
                        val editorVm: BillEditorViewModel = viewModel(key = "editor-${request.key}", factory = viewModelFactory { initializer {
                            BillEditorViewModel(null, null, BillTab.EXPENSE, bills, graph.commands, graph.settings.observeSettings(),
                                graph.overview.observeFxContext(), graph.clock, createSavedStateHandle(), graph.billImages,
                                request.prefill, request.operation)
                        } })
                        BillEditorScreen(editorVm, onDone = {
                            if (request.manual) onClose(maxOf(1, vm.state.value.saved)) else { editor = null; vm.refresh() }
                        }, onManageCategories = {})
                    } else AiShareScreen(vm, onEdit = { prefill, operation -> editor = EditorRequest(++nextKey, prefill, operation, manual = false) },
                        onManual = ::openManual, onFinished = onClose)
                }
            }
        }
    }
}
