package dev.valnook.feature.wallet

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.WalletRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface WalletFaceNotice {
    data object Added : WalletFaceNotice
    data object Deleted : WalletFaceNotice
    data object Exported : WalletFaceNotice
    data object ExportFailed : WalletFaceNotice
    data class Failed(val code: ErrorCode?) : WalletFaceNotice
}

/** The face library behind Settings: every face a card has used or the library received, until deleted here. */
class WalletFaceLibraryViewModel(private val repository: WalletRepository) : ViewModel() {
    private val mutableFaces=MutableStateFlow<List<WalletFace>?>(null)
    val faces=mutableFaces.asStateFlow()
    val failed=MutableStateFlow(false)
    val busy=MutableStateFlow(false)
    val notice=MutableStateFlow<WalletFaceNotice?>(null)
    /** A cropped upload waiting for its name; kept here so it survives rotation. */
    val pending=MutableStateFlow<WalletImage?>(null)
    val pendingError=MutableStateFlow<ErrorCode?>(null)
    private val pendingFailed=MutableStateFlow(false)

    init {
        viewModelScope.launch {
            repository.observeFaces().retryWhen { _, _ -> failed.value=true; delay(1500); true }
                .collect { mutableFaces.value=it; failed.value=false }
        }
    }

    fun cropped(image: WalletImage) { pending.value=image; pendingError.value=null; pendingFailed.value=false }
    fun cancelAdd() { if(!busy.value) { pending.value=null; pendingError.value=null } }
    fun add(label: String) {
        val image=pending.value?:return
        mutate({ repository.addFace(label,image); pending.value=null; notice.value=WalletFaceNotice.Added }) {
            pendingError.value=it?:ErrorCode.OPERATION_CONFLICT
        }
    }
    fun delete(key: String)=mutate({ repository.deleteFace(key); notice.value=WalletFaceNotice.Deleted }) {
        notice.value=WalletFaceNotice.Failed(it)
    }
    /** Writes the stored WebP unchanged; [open] returns the stream of the file the user picked. */
    fun export(key: String, open: () -> OutputStream?)=mutate({
        val image=repository.image(key)?:throw DomainException(ErrorCode.NOT_FOUND)
        withContext(Dispatchers.IO) { requireNotNull(open()).use { it.write(image.bytes) } }
        notice.value=WalletFaceNotice.Exported
    }) { notice.value=WalletFaceNotice.ExportFailed }

    private fun mutate(action: suspend () -> Unit, onError: (ErrorCode?) -> Unit) {
        if(busy.value)return
        busy.value=true; notice.value=null
        viewModelScope.launch {
            try { action() }
            catch(c: CancellationException) { throw c }
            catch(e: Exception) { onError((e as? DomainException)?.code) }
            finally { busy.value=false }
        }
    }
}

/** Top bar action of the face library page, hosted by the app's root toolbar. */
data class WalletFaceLibraryToolbar(val enabled: Boolean, val add: () -> Unit)

@Composable fun WalletFaceLibraryActions(state: WalletFaceLibraryToolbar?) {
    if(state==null)return
    val label=stringResource(R.string.wallet_upload_card_face)
    IconButton(state.add,Modifier.testTag("wallet-face-add").semantics { contentDescription=label },enabled=state.enabled) { AddIcon(enabled=state.enabled) }
}

@Composable fun WalletFaceLibraryScreen(vm: WalletFaceLibraryViewModel, cache: WalletImageCache,
    onToolbarChange: (WalletFaceLibraryToolbar?) -> Unit = {}) {
    val faces by vm.faces.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected->
        if(selected!=null){runCatching{context.contentResolver.takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=selected.toString()}
    }
    val saver=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/webp")) { target->
        val key=exporting;exporting=null
        if(target!=null&&key!=null)vm.export(key){context.contentResolver.openOutputStream(target,"wt")}
    }
    val full=(faces?.size?:0)>=WalletRules.MAX_FACES
    val toolbar=remember(busy,faces==null,full) {
        WalletFaceLibraryToolbar(!busy&&faces!=null&&!full) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    SideEffect { onToolbarChange(toolbar) }
    DisposableEffect(Unit) { onDispose { onToolbarChange(null) } }

    val list=faces
    val sectionTitles=listOf(true to stringResource(R.string.wallet_use),false to stringResource(R.string.wallet_not_use))
    if(list==null) {
        Box(Modifier.fillMaxSize().padding(pageContentPadding())) {
            if(failed) HintMessage(stringResource(R.string.wallet_unable_load_card_faces_reopen),isError=true)
            else CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    } else LazyColumn(Modifier.fillMaxSize().testTag("wallet-face-library"),contentPadding=pageContentPadding()) {
        // Each face is an item of its own, so only the faces on screen are composed and decoded; the pieces of a card
        // must meet, so items space themselves (see glassCardItems).
        val gap=Space.sm
        item("hint") {
            Text(stringResource(R.string.wallet_card_faces_stay_here_after)+" ${list.size} / ${WalletRules.MAX_FACES}",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let { item("notice") { Box(Modifier.padding(top=gap)) { FaceNotice(it) } } }
        if(full) item("full") { Box(Modifier.padding(top=gap)) { HintMessage(faceErrorText(ErrorCode.WALLET_FACE_LIBRARY_FULL)) } }
        if(list.isEmpty()) item("empty") { Box(Modifier.padding(top=gap)) {
            EmptyState(stringResource(R.string.wallet_no_card_faces_yet_tap))
        } }
        sectionTitles.forEach { (used,title)->
            val section=list.filter { it.inUse==used }
            if(section.isNotEmpty()) {
                item("title-$used") {
                    Text(title,Modifier.padding(start=Space.xs,top=Space.md+gap),style=MaterialTheme.typography.titleSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                glassCardItems(section,{it.key},"face-$used",gap,divider={HorizontalDivider(Modifier.padding(horizontal=Space.cardInset))}) { face->
                    FaceRow(face,cache,!busy,{exporting=face.key;saver.launch(faceFileName(face))},{deleting=face.key})
                }
            }
        }
    }

    list?.firstOrNull { it.key==deleting }?.let { face->
        AnimatedGlassDialog(true,{if(!busy)deleting=null}) { GlassCard { Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.wallet_delete_card_face),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.wallet_removed_from_library_from_later),
                Modifier.padding(vertical=16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({deleting=null},enabled=!busy){Text(stringResource(R.string.wallet_cancel))}
                TextButton({deleting=null;vm.delete(face.key)},enabled=!busy,modifier=Modifier.testTag("wallet-face-delete-confirm")) {
                    Text(stringResource(R.string.wallet_delete),color=MaterialTheme.colorScheme.error)
                }
            }
        } } }
    }
    uri?.let { selected->WalletCrop(selected,vm::cropped) {
        runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(selected),Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=null
    } }
    pending?.let { FaceNameDialog(vm,cache,it) }
}

@Composable private fun FaceRow(face: WalletFace, cache: WalletImageCache, enabled: Boolean, onExport: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start=Space.cardInset,end=Space.xs,top=Space.md,bottom=Space.md).testTag("wallet-face-${face.key}"),
        verticalAlignment=Alignment.CenterVertically) {
        WalletFaceThumbnail(face.key,cache,Modifier.width(88.dp))
        Column(Modifier.weight(1f).padding(horizontal=Space.md),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(faceLabel(face),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(if(face.inUse)stringResource(R.string.wallet_used_by)+face.cardNames.joinToString(stringResource(R.string.wallet_name_separator))
                else stringResource(R.string.wallet_added)+faceDate(face.addedAtMs),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        val exportLabel=stringResource(R.string.wallet_save_image)
        IconButton(onExport,Modifier.testTag("wallet-face-export-${face.key}").semantics { contentDescription=exportLabel },enabled=enabled) { FaIcon(FaIcons.download) }
        DeleteIconButton(stringResource(R.string.wallet_delete_card_face_2),onDelete,Modifier.testTag("wallet-face-delete-${face.key}"),enabled=enabled&&!face.inUse)
    }
}

/** Names the cropped upload before it joins the library. */
@Composable private fun FaceNameDialog(vm: WalletFaceLibraryViewModel, cache: WalletImageCache, image: WalletImage) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.pendingError.collectAsStateWithLifecycle()
    var name by rememberSaveable(image.key) { mutableStateOf("") }
    AnimatedGlassDialog(true,{vm.cancelAdd()}) {
        // Read inside the dialog window, whose focus holds the name field.
        val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        GlassCard { Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.85f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wallet_add_card_faces),style=MaterialTheme.typography.titleLarge)
            WalletCardView(WalletCard(0,name,image.key,null,0,0,0,0),cache,preview=image)
            OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.wallet_card_face_name))},
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("wallet-face-name"),
                supportingText={Text(stringResource(R.string.wallet_1_40_characters_only_shown_2))})
            error?.let { HintMessage(faceErrorText(it),isError=true) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({vm.cancelAdd()},enabled=!busy){Text(stringResource(R.string.wallet_cancel))}
                Button({focus.clearFocus();keyboard?.hide();vm.add(name)},enabled=!busy&&runCatching{WalletRules.name(name)}.isSuccess,
                    modifier=Modifier.testTag("wallet-face-save")) {
                    Text(if(busy)stringResource(R.string.wallet_saving)else stringResource(R.string.wallet_save))
                }
            }
        } }
    }
}

/** The library as a picker for the card editor: choosing a face applies it to the draft. */
@Composable internal fun WalletFacePickerDialog(faces: List<WalletFace>?, cache: WalletImageCache, selected: String?,
    onPick: (WalletFace) -> Unit, onDismiss: () -> Unit) {
    AnimatedGlassDialog(true,onDismiss) {
        GlassCard { Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.8f).dp).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wallet_card_faces),style=MaterialTheme.typography.titleLarge)
            when {
                faces==null -> CircularProgressIndicator()
                faces.isEmpty() -> Text(stringResource(R.string.wallet_no_card_faces_yet_faces),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())) {
                    faces.forEach { face->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role=Role.Button){onPick(face)}.padding(vertical=Space.sm).testTag("wallet-face-pick-${face.key}"),
                            verticalAlignment=Alignment.CenterVertically) {
                            WalletFaceThumbnail(face.key,cache,Modifier.width(72.dp))
                            Column(Modifier.weight(1f).padding(horizontal=Space.md)) {
                                Text(faceLabel(face),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(face.inUse)stringResource(R.string.wallet_used_by)+face.cardNames.joinToString(stringResource(R.string.wallet_name_separator)) else stringResource(R.string.wallet_not_use),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            if(face.key==selected)SelectedIcon(Modifier.padding(end=Space.sm).size(18.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onDismiss){Text(stringResource(R.string.wallet_cancel))}
            }
        } }
    }
}

@Composable internal fun WalletFaceThumbnail(key: String, cache: WalletImageCache, modifier: Modifier=Modifier) {
    BoxWithConstraints(modifier.aspectRatio(WalletRules.ASPECT).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val edge=with(LocalDensity.current){maxWidth.roundToPx()}
        val image by produceState(cache.peek(key,edge),key,edge) { value=runCatching{cache.load(key,edge)}.getOrNull()?:value }
        image?.let { Image(it.bitmap,contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
    }
}

@Composable internal fun faceErrorText(code: ErrorCode?): String=when(code) {
    ErrorCode.WALLET_FACE_DUPLICATE -> stringResource(R.string.wallet_card_face_already_library)
    ErrorCode.WALLET_FACE_LIBRARY_FULL -> stringResource(R.string.wallet_library_full_faces_delete_faces, WalletRules.MAX_FACES)
    ErrorCode.WALLET_FACE_IN_USE -> stringResource(R.string.wallet_card_face_use_change_face)
    ErrorCode.FORMAT -> stringResource(R.string.wallet_unable_process_image_choose_another)
    else -> stringResource(R.string.wallet_changes_could_not_saved_your)
}

@Composable private fun FaceNotice(notice: WalletFaceNotice) {
    when(notice) {
        WalletFaceNotice.Added -> HintMessage(stringResource(R.string.wallet_added_card_faces))
        WalletFaceNotice.Deleted -> HintMessage(stringResource(R.string.wallet_card_face_deleted))
        WalletFaceNotice.Exported -> HintMessage(stringResource(R.string.wallet_image_saved))
        WalletFaceNotice.ExportFailed -> HintMessage(stringResource(R.string.wallet_unable_save_image_please_retry),isError=true)
        is WalletFaceNotice.Failed -> HintMessage(faceErrorText(notice.code),isError=true)
    }
}

@Composable private fun faceLabel(face: WalletFace)=face.label.ifEmpty { stringResource(R.string.wallet_untitled_card_face) }

private val FACE_DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd")
private fun faceDate(ms: Long)=FACE_DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Keeps the face name readable in the suggested file name while dropping characters file systems reject. */
internal fun faceFileName(face: WalletFace): String {
    val base=face.label.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().trim('.')
    return (base.ifEmpty { "valnook-card-face" })+".webp"
}
