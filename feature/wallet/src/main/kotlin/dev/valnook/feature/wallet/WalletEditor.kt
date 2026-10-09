package dev.valnook.feature.wallet

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.data.image.WalletImages
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*

@Composable internal fun WalletEditor(vm:WalletViewModel,cache:WalletImageCache,value:WalletDraft) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.operationFailed.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){selected->
        if(selected!=null){runCatching{context.contentResolver.takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=selected.toString()}
    }
    AnimatedGlassDialog(true,{if(!busy&&uri==null)vm.draft.value=null}) {
        GlassCard { Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.85f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(value.id==null)wording("Add card","添加卡片")else wording("Edit card","编辑卡片"),style=MaterialTheme.typography.titleLarge)
            WalletCardView(WalletCard(value.id?:0,value.name,value.imageKey,value.boundId,0,0,0,0),cache,preview=value.image)
            OutlinedTextField(value.name,{vm.draft.value=value.copy(name=it)},label={Text(wording("Card name","卡片名称"))},
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("wallet-name"),
                supportingText={Text(wording("1–40 characters · only shown outside the card","1–40 个字符，仅在卡面外显示"))})
            Row {
                TextButton({focus.clearFocus();keyboard?.hide();picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!busy,modifier=Modifier.testTag("wallet-photo")){Text(wording("Choose image","选择图片"))}
                if(value.imageKey!=null||value.image!=null)TextButton({vm.draft.value=value.copy(imageKey=null,image=null)},enabled=!busy){Text(wording("Remove image","移除图片"))}
            }
            Text(wording("Use a blank or redacted card face. Images stay on this device and are included in your backups.","建议使用空白或已遮挡敏感信息的卡面。图片在本机处理，并随完整备份保存。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val account=snapshot?.cash?.firstOrNull{it.id==value.boundId}
            val parent=snapshot?.accounts?.firstOrNull{it.id==account?.account_id}
            TextButton({choosing=true},enabled=!busy,modifier=Modifier.testTag("wallet-bind")) {
                Text(if(account==null)wording("Link a subaccount (optional)","绑定子账户（可选）")else "${parent?.name} · ${account.name} · ${account.currency.code}")
            }
            if(value.boundId!=null&&vm.cards.value.orEmpty().any{it.id!=value.id&&it.boundCashAccountId==value.boundId})
                Text(wording("These cards show all records from the same subaccount.","这些卡片将展示同一子账户的全部记录。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(error) HintMessage(wording("Could not save. Your draft is kept; retry or reopen if the card changed elsewhere.","保存失败，已保留填写内容。请重试；若卡片已被其他操作修改，请重新编辑。"),isError=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({vm.draft.value=null},enabled=!busy){Text(wording("Cancel","取消"))}
                Button({focus.clearFocus();keyboard?.hide();vm.save()},enabled=!busy&&runCatching{WalletRules.name(value.name)}.isSuccess,modifier=Modifier.testTag("wallet-save")){
                    Text(if(busy)wording("Saving…","保存中…")else wording("Save","保存"))
                }
            }
        } }
    }
    if(choosing)AnimatedGlassDialog(true,{choosing=false}) {
        GlassCard { Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(wording("Link a subaccount","绑定子账户"),style=MaterialTheme.typography.titleLarge)
            TextButton({vm.draft.value=value.copy(boundId=null);choosing=false}){Text(wording("No linked account","不绑定"))}
            snapshot?.accounts?.forEach { parent->
                val accounts=snapshot?.cash.orEmpty().filter{it.account_id==parent.id}
                if(accounts.isNotEmpty())Text(parent.name,Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall)
                accounts.forEach{account->TextButton({vm.draft.value=value.copy(boundId=account.id);choosing=false},Modifier.fillMaxWidth()) {
                    Text("${account.name} · ${account.currency.code} · "+if(account.creditProfile!=null)wording("Credit","信用")else wording("Savings","储蓄"))
                }}
            }
        } }
    }
    uri?.let { selected->WalletCrop(selected,{image->vm.draft.value=vm.draft.value?.copy(imageKey=image.key,image=image)}){
        runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(selected),Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=null
    } }
}

@Composable private fun WalletCrop(uri:String,onImage:(WalletImage)->Unit,onClose:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var source by remember(uri){mutableStateOf<Bitmap?>(null)}
    var failed by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) }
    var zoom by rememberSaveable{mutableFloatStateOf(1f)}
    var x by rememberSaveable{mutableFloatStateOf(0f)};var y by rememberSaveable{mutableFloatStateOf(0f)}
    LaunchedEffect(uri){try{source=withContext(Dispatchers.IO){WalletImages.read(context,Uri.parse(uri))}}
        catch(c:CancellationException){throw c}catch(_:Exception){failed=true}}
    AnimatedGlassDialog(true,{if(!busy)onClose()}) {
        GlassCard { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(wording("Adjust card face","调整卡面"),style=MaterialTheme.typography.titleLarge)
            val bitmap=source
            if(bitmap!=null) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(WalletRules.ASPECT).clip(RoundedCornerShape(18.dp))
                    .testTag("wallet-crop").pointerInput(busy){detectTransformGestures{_,pan,scale,_->if(!busy){
                        zoom=(zoom*scale).coerceIn(1f,5f);x=(x+pan.x/size.width).coerceIn(-3f,3f);y=(y+pan.y/size.height).coerceIn(-3f,3f)
                    }}}) {drawIntoCanvas{it.nativeCanvas.drawBitmap(bitmap,null,WalletImages.cropRect(bitmap.width,bitmap.height,size.width,size.height,zoom,x,y),android.graphics.Paint(3))}}
                Text(wording("Move and pinch to crop · fixed card proportions","移动、双指缩放裁剪 · 固定卡片比例"),style=MaterialTheme.typography.bodySmall)
                Slider(zoom,{zoom=it},valueRange=1f..5f,enabled=!busy)
                TextButton({scope.launch{busy=true;try{source=withContext(Dispatchers.Default){Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply{postRotate(90f)},true)};zoom=1f;x=0f;y=0f}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("wallet-rotate")){Text(wording("Rotate 90°","旋转 90°"))}
            }else if(!failed)CircularProgressIndicator()
            if(failed)HintMessage(wording("Unable to process image. Use a static JPEG, PNG or WebP under 20 MiB and 40 MP.","无法处理图片，请选择 20 MiB、4000 万像素以内的静态 JPEG、PNG 或 WebP。"),isError=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                TextButton(onClose,enabled=!busy){Text(wording("Cancel","取消"))}
                Button({scope.launch{busy=true;failed=false;try{val image=withContext(Dispatchers.Default){WalletImages.encode(requireNotNull(bitmap),zoom,x,y)};onImage(image);onClose()}
                    catch(c:CancellationException){throw c}catch(_:Exception){failed=true}finally{busy=false}}},enabled=bitmap!=null&&!busy,modifier=Modifier.testTag("wallet-crop-confirm")){Text(wording("Use image","使用图片"))}
            }
        } }
    }
}
