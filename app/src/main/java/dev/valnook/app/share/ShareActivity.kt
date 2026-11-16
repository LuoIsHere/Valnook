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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dagger.hilt.android.AndroidEntryPoint
import dev.valnook.app.appearance.ThemePreferences
import dev.valnook.app.appearance.configurationOverride
import dev.valnook.app.di.AppSessionManager
import dev.valnook.designsystem.AccountImageLoader
import dev.valnook.designsystem.BackButton
import dev.valnook.designsystem.LocalAccountImageLoader
import dev.valnook.designsystem.LocalPageBottomSpace
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.ai.AiFeatures
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.feature.ai.AiChatScreen
import dev.valnook.feature.ai.AiChatToolbarState
import dev.valnook.feature.ai.AiChatViewModel
import dev.valnook.feature.ai.AiDraftSaver
import dev.valnook.feature.ai.AiNotices
import dev.valnook.feature.ai.PictureNotice
import dev.valnook.feature.bills.BillEditorScreen
import dev.valnook.feature.bills.BillEditorViewModel
import dev.valnook.feature.bills.BillImageCodec
import dev.valnook.feature.bills.BillTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * "Valnook bill" in the system share sheet (turned off in Settings → Quick bookkeeping): text, or up to four
 * pictures. It runs in the sharing app's task, so leaving it goes back there. With AI on it opens the AI chat and
 * sends the share there as the user's message (pictures only when the model reads them); otherwise the bill page
 * opens with the text in the note and the pictures on the bill.
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

    private fun close(saved: Boolean) {
        if (saved) Toast.makeText(this, dev.valnook.feature.ai.R.string.ai_share_saved, Toast.LENGTH_SHORT).show()
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
/** The bill page in the share: a new bill from [prefill], or saved bill [billId] to change again. */
private data class EditorRequest(val key: Int, val prefill: String?, val operation: String?, val closesShare: Boolean,
    val billId: Long? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareRoot(sessions: AppSessionManager, shared: SharedContent, onClose: (saved: Boolean) -> Unit) {
    val active by sessions.session.collectAsStateWithLifecycle()
    val graph = active.graph
    val settings by graph.settings.observeSettings().collectAsStateWithLifecycle(null)
    val features by remember(graph.ai) { graph.ai?.observeFeatures() ?: flowOf(AiFeatures()) }.collectAsStateWithLifecycle(null)
    val bills = graph.bills
    ValnookTheme(redGain = settings?.gainLossColors == GainLossColorScheme.RED_GAIN) {
        val loaded = features
        if (bills == null || loaded == null) { Surface(Modifier.fillMaxSize()) {}; return@ValnookTheme }
        val resolver = LocalContext.current.contentResolver
        val sources = remember(shared) { shared.pictures.map { uri -> suspend { BillImageCodec.prepare(resolver, uri) } } }
        val saver = remember(graph.sessionId) {
            AiDraftSaver(bills, graph.commands, graph.settings.observeSettings(), graph.overview.observeFxContext(), graph.clock,
                graph.billImages)
        }
        // Accounts with a picture of their own show it in the account picker, as in the app.
        val accountImages = remember(graph.sessionId) { AccountImageLoader(graph.overview::accountIconImage) }
        // Decided once, when the page opens: with AI on, the AI chat (pictures once the model reads them), otherwise
        // straight to the bill page.
        val ai = graph.ai
        val assistant = graph.assistant
        val context = LocalContext.current
        val offered = rememberSaveable { ai != null && assistant != null &&
            loaded.enabled && (shared.pictures.isEmpty() || loaded.shareImage) }
        // The first pictures to go to the model ask first, as in the chat; declining puts them on a bill instead.
        var asking by rememberSaveable { mutableStateOf(offered && shared.pictures.isNotEmpty() && !AiNotices.picturesSeen(context)) }
        var declined by rememberSaveable { mutableStateOf(false) }
        val useAi = offered && !declined
        var editor by remember { mutableStateOf<EditorRequest?>(null) }
        var nextKey by rememberSaveable { mutableIntStateOf(0) }
        var chatToolbar by remember { mutableStateOf<AiChatToolbarState?>(null) }

        val chat: AiChatViewModel? = if (useAi && !asking && ai != null && assistant != null) viewModel(key = "share-chat",
            factory = viewModelFactory { initializer {
                AiChatViewModel(assistant, ai, bills, saver, graph.billImages, createSavedStateHandle())
            } }) else null
        if (asking) PictureNotice({ asking = false; declined = true }) { AiNotices.markPicturesSeen(context); asking = false }
        else if (chat != null) LaunchedEffect(chat) { chat.sendShared(shared.text, sources) }
        else LaunchedEffect(Unit) {
            // Without AI the share is a bill to write: its pictures are stored, the page opens with them.
            if (editor == null) editor = EditorRequest(++nextKey, manualPrefill(shared.text, sources, graph.billImages, saver),
                null, closesShare = true)
        }

        val request = editor
        val back: () -> Unit = {
            if (request != null && !request.closesShare) { editor = null; chat?.refresh() } else onClose(false)
        }
        BackHandler(onBack = back)
        Scaffold(topBar = {
            TopAppBar(title = { Text(if (request?.billId != null) stringResource(dev.valnook.app.R.string.title_edit_bill)
                else if (request != null) stringResource(dev.valnook.feature.ai.R.string.ai_share_new_bill)
                else chatToolbar?.title ?: stringResource(dev.valnook.feature.ai.R.string.ai_chat_title)) }, navigationIcon = {
                BackButton(stringResource(dev.valnook.feature.ai.R.string.ai_share_back), back)
            })
        }) { padding ->
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            CompositionLocalProvider(dev.valnook.feature.bills.LocalBillImages provides graph.billImages,
                LocalAccountImageLoader provides accountImages, LocalPageBottomSpace provides bottom) {
                Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).imePadding()
                    .testTag("share-root")) {
                    if (request != null) {
                        val editorVm: BillEditorViewModel = viewModel(key = "editor-${request.key}", factory = viewModelFactory { initializer {
                            BillEditorViewModel(request.billId, null, BillTab.EXPENSE, bills, graph.commands, graph.settings.observeSettings(),
                                graph.overview.observeFxContext(), graph.clock, createSavedStateHandle(), graph.billImages,
                                request.prefill, request.operation)
                        } })
                        BillEditorScreen(editorVm, onDone = {
                            if (request.closesShare) onClose(true) else { editor = null; chat?.refresh() }
                        }, onManageCategories = {})
                    } else if (chat != null) AiChatScreen(chat,
                        onEdit = { prefill, operation -> editor = EditorRequest(++nextKey, prefill, operation, closesShare = false) },
                        // The bill details live in the app itself; here a saved draft only says so.
                        onOpenBill = null,
                        onManual = { prefill -> editor = EditorRequest(++nextKey, prefill, null, closesShare = false) },
                        onToolbar = { chatToolbar = it },
                        // A saved card's bill opens here too, not in the app.
                        onEditBill = { billId -> editor = EditorRequest(++nextKey, null, null, closesShare = false, billId = billId) })
                    else Surface(Modifier.fillMaxSize()) {}
                }
            }
        }
    }
}

/** The bill page's form for a share as it came: the words in the note, the pictures (stored first) on the bill. */
private suspend fun manualPrefill(text: String, sources: List<suspend () -> ByteArray>,
    library: dev.valnook.domain.repository.BillImageLibrary?, saver: AiDraftSaver): String {
    val stored = mutableListOf<BillImage>()
    if (library != null) sources.take(BillImageRules.MAX_PER_BILL).forEach { source ->
        try { library.store(source()).let { image -> if (stored.none { it.key == image.key }) stored += image } }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
    }
    val draft = AiBillDraft(BillType.EXPENSE, null, "", occurredAt = LocalDateTime.now().withSecond(0).withNano(0), note = text)
    return saver.prefill(draft, stored).also {
        // The bill page holds them from here; storing held them only until then.
        library?.saved(stored.map { it.key })
    }
}
