package dev.valnook.feature.wallet

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
    val label=wording("Upload card face","上传卡面")
    IconButton(state.add,Modifier.testTag("wallet-face-add").semantics { contentDescription=label },enabled=state.enabled) { Text("＋") }
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
    val sectionTitles=listOf(true to wording("In use","使用中"),false to wording("Not in use","未使用"))
    if(list==null) {
        Box(Modifier.fillMaxSize().padding(pageContentPadding())) {
            if(failed) HintMessage(wording("Unable to load card faces. Reopen this page to retry.","卡面加载失败，请重新打开此页面重试。"),isError=true)
            else CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    } else LazyColumn(Modifier.fillMaxSize().testTag("wallet-face-library"),contentPadding=pageContentPadding(),
        verticalArrangement=Arrangement.spacedBy(Space.sm)) {
        item("hint") {
            Text(wording("Card faces stay here after a card changes face or is deleted, until you delete them. A face in use cannot be deleted.",
                "卡片换掉卡面或被删除后，卡面仍保留在这里，直到你删除它。正在使用的卡面不能删除。")+" ${list.size} / ${WalletRules.MAX_FACES}",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let { item("notice") { FaceNotice(it) } }
        if(full) item("full") { HintMessage(faceErrorText(ErrorCode.WALLET_FACE_LIBRARY_FULL)) }
        if(list.isEmpty()) item("empty") {
            EmptyState(wording("No card faces yet. Tap ＋ to upload one, or choose an image when editing a card.","还没有卡面。点按 ＋ 上传，或在编辑卡片时选择图片。"))
        }
        sectionTitles.forEach { (used,title)->
            val section=list.filter { it.inUse==used }
            if(section.isNotEmpty()) {
                item("title-$used") {
                    Text(title,Modifier.padding(start=Space.xs,top=Space.md),style=MaterialTheme.typography.titleSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item("section-$used") {
                    GlassCard { section.forEachIndexed { index,face->
                        if(index>0)HorizontalDivider(Modifier.padding(horizontal=Space.cardInset))
                        FaceRow(face,cache,!busy,{exporting=face.key;saver.launch(faceFileName(face))},{deleting=face.key})
                    } }
                }
            }
        }
    }

    list?.firstOrNull { it.key==deleting }?.let { face->
        AnimatedGlassDialog(true,{if(!busy)deleting=null}) { GlassCard { Column(Modifier.padding(24.dp)) {
            Text(wording("Delete this card face?","删除这张卡面？"),style=MaterialTheme.typography.titleLarge)
            Text(wording("It is removed from the library and from later backups. Existing backups are unchanged.","卡面将从卡面库删除，之后的备份不再包含它；已有备份不受影响。"),
                Modifier.padding(vertical=16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({deleting=null},enabled=!busy){Text(wording("Cancel","取消"))}
                TextButton({deleting=null;vm.delete(face.key)},enabled=!busy,modifier=Modifier.testTag("wallet-face-delete-confirm")) {
                    Text(wording("Delete","删除"),color=MaterialTheme.colorScheme.error)
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
            Text(if(face.inUse)wording("Used by ","用于：")+face.cardNames.joinToString(wording(", ","、"))
                else wording("Added ","添加于 ")+faceDate(face.addedAtMs),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        val exportLabel=wording("Save image","保存图片")
        IconButton(onExport,Modifier.testTag("wallet-face-export-${face.key}").semantics { contentDescription=exportLabel },enabled=enabled) { DownloadIcon() }
        DeleteIconButton(wording("Delete card face","删除卡面"),onDelete,Modifier.testTag("wallet-face-delete-${face.key}"),enabled=enabled&&!face.inUse)
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
            Text(wording("Add to card faces","添加到卡面库"),style=MaterialTheme.typography.titleLarge)
            WalletCardView(WalletCard(0,name,image.key,null,0,0,0,0),cache,preview=image)
            OutlinedTextField(name,{name=it},label={Text(wording("Card face name","卡面名称"))},
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("wallet-face-name"),
                supportingText={Text(wording("1–40 characters · only shown in the library","1–40 个字符，仅在卡面库中显示"))})
            error?.let { HintMessage(faceErrorText(it),isError=true) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({vm.cancelAdd()},enabled=!busy){Text(wording("Cancel","取消"))}
                Button({focus.clearFocus();keyboard?.hide();vm.add(name)},enabled=!busy&&runCatching{WalletRules.name(name)}.isSuccess,
                    modifier=Modifier.testTag("wallet-face-save")) {
                    Text(if(busy)wording("Saving…","保存中…")else wording("Save","保存"))
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
            Text(wording("Card faces","卡面库"),style=MaterialTheme.typography.titleLarge)
            when {
                faces==null -> CircularProgressIndicator()
                faces.isEmpty() -> Text(wording("No card faces yet. Faces you apply or upload in Settings › Card faces appear here.","还没有卡面。应用过的卡面和在“设置 › 卡面库”上传的卡面会显示在这里。"),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())) {
                    faces.forEach { face->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role=Role.Button){onPick(face)}.padding(vertical=Space.sm).testTag("wallet-face-pick-${face.key}"),
                            verticalAlignment=Alignment.CenterVertically) {
                            WalletFaceThumbnail(face.key,cache,Modifier.width(72.dp))
                            Column(Modifier.weight(1f).padding(horizontal=Space.md)) {
                                Text(faceLabel(face),style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(face.inUse)wording("Used by ","用于：")+face.cardNames.joinToString(wording(", ","、")) else wording("Not in use","未使用"),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            if(face.key==selected)Text("✓",Modifier.padding(end=Space.sm),style=MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onDismiss){Text(wording("Cancel","取消"))}
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
    ErrorCode.WALLET_FACE_DUPLICATE -> wording("This card face is already in the library.","这张卡面已在卡面库中。")
    ErrorCode.WALLET_FACE_LIBRARY_FULL -> wording("The library is full (${WalletRules.MAX_FACES} faces). Delete faces you no longer need in Settings › Card faces.",
        "卡面库已满（${WalletRules.MAX_FACES} 张），请先在“设置 › 卡面库”中删除不用的卡面。")
    ErrorCode.WALLET_FACE_IN_USE -> wording("This card face is in use. Change the face of the cards using it first.","这张卡面正在使用，请先为使用它的卡片更换卡面。")
    ErrorCode.FORMAT -> wording("Unable to process image. Choose another image.","无法处理图片，请换一张图片。")
    else -> wording("Changes could not be saved. Your data is unchanged; please retry.","保存失败，已有数据未改变，请重试。")
}

@Composable private fun FaceNotice(notice: WalletFaceNotice) {
    when(notice) {
        WalletFaceNotice.Added -> HintMessage(wording("Added to card faces.","已添加到卡面库。"))
        WalletFaceNotice.Deleted -> HintMessage(wording("Card face deleted.","卡面已删除。"))
        WalletFaceNotice.Exported -> HintMessage(wording("Image saved.","图片已保存。"))
        WalletFaceNotice.ExportFailed -> HintMessage(wording("Unable to save the image. Please retry.","图片保存失败，请重试。"),isError=true)
        is WalletFaceNotice.Failed -> HintMessage(faceErrorText(notice.code),isError=true)
    }
}

@Composable private fun faceLabel(face: WalletFace)=face.label.ifEmpty { wording("Untitled card face","未命名卡面") }

private val FACE_DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd")
private fun faceDate(ms: Long)=FACE_DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Keeps the face name readable in the suggested file name while dropping characters file systems reject. */
internal fun faceFileName(face: WalletFace): String {
    val base=face.label.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").trim().trim('.')
    return (base.ifEmpty { "valnook-card-face" })+".webp"
}

/** Arrow into a tray, drawn like the other line icons. */
@Composable private fun DownloadIcon() {
    val color=LocalContentColor.current
    Canvas(Modifier.size(20.dp)) {
        val w=size.width;val h=size.height;val stroke=1.7.dp.toPx()
        drawLine(color,Offset(w*.5f,h*.12f),Offset(w*.5f,h*.62f),stroke,StrokeCap.Round)
        drawLine(color,Offset(w*.3f,h*.44f),Offset(w*.5f,h*.64f),stroke,StrokeCap.Round)
        drawLine(color,Offset(w*.7f,h*.44f),Offset(w*.5f,h*.64f),stroke,StrokeCap.Round)
        drawLine(color,Offset(w*.14f,h*.7f),Offset(w*.14f,h*.86f),stroke,StrokeCap.Round)
        drawLine(color,Offset(w*.14f,h*.86f),Offset(w*.86f,h*.86f),stroke,StrokeCap.Round)
        drawLine(color,Offset(w*.86f,h*.7f),Offset(w*.86f,h*.86f),stroke,StrokeCap.Round)
    }
}
