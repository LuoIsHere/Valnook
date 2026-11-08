package dev.valnook.feature.wallet

import androidx.compose.ui.res.stringResource
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
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*

@Composable internal fun WalletEditor(vm:WalletViewModel,cache:WalletImageCache,value:WalletDraft) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.operationFailed.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var pickingFace by rememberSaveable { mutableStateOf(false) }
    val failure by vm.failure.collectAsStateWithLifecycle()
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){selected->
        if(selected!=null){runCatching{context.contentResolver.takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=selected.toString()}
    }
    AnimatedGlassDialog(true,{if(!busy&&uri==null)vm.draft.value=null}) {
        // The dialog is its own window: only its focus manager and keyboard controller reach the name field.
        val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        GlassCard { Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.85f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(value.id==null)stringResource(R.string.wallet_add_card)else stringResource(R.string.wallet_edit_card),style=MaterialTheme.typography.titleLarge)
            WalletCardView(WalletCard(value.id?:0,value.name,value.imageKey,value.boundId,0,0,0,0,faceStyle=value.faceStyle),cache,preview=value.image)
            OutlinedTextField(value.name,{vm.draft.value=value.copy(name=it)},label={Text(stringResource(R.string.wallet_card_name))},
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("wallet-name"),
                supportingText={Text(stringResource(R.string.wallet_1_40_characters_only_shown))})
            FlowRow {
                TextButton({focus.clearFocus();keyboard?.hide();picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!busy,modifier=Modifier.testTag("wallet-photo")){Text(stringResource(R.string.wallet_choose_image))}
                TextButton({focus.clearFocus();keyboard?.hide();pickingFace=true},enabled=!busy,modifier=Modifier.testTag("wallet-face-library-pick")){Text(stringResource(R.string.wallet_from_card_faces))}
                if(value.imageKey!=null||value.image!=null)TextButton({vm.draft.value=value.copy(imageKey=null,image=null)},enabled=!busy){Text(stringResource(R.string.wallet_remove_image))}
            }
            WalletFacePicker(value.faceStyle, !busy, value.imageKey!=null||value.image!=null) { style -> vm.draft.value=value.copy(faceStyle=style,imageKey=null,image=null) }
            Text(stringResource(R.string.wallet_use_blank_redacted_card_face),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val account=snapshot?.cash?.firstOrNull{it.id==value.boundId}
            val parent=snapshot?.accounts?.firstOrNull{it.id==account?.account_id}
            TextButton({choosing=true},enabled=!busy,modifier=Modifier.testTag("wallet-bind")) {
                Text(if(account==null)stringResource(R.string.wallet_link_subaccount_optional)else "${parent?.name} · ${account.name} · ${account.currency.code}")
            }
            if(value.boundId!=null&&cards.orEmpty().any{it.id!=value.id&&it.boundCashAccountId==value.boundId})
                Text(stringResource(R.string.wallet_these_cards_show_all_records),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(error) HintMessage(if(failure==ErrorCode.WALLET_FACE_LIBRARY_FULL)faceErrorText(failure)
                else stringResource(R.string.wallet_could_not_save_your_draft),isError=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({vm.draft.value=null},enabled=!busy){Text(stringResource(R.string.wallet_cancel))}
                Button({focus.clearFocus();keyboard?.hide();vm.save()},enabled=!busy&&runCatching{WalletRules.name(value.name)}.isSuccess,modifier=Modifier.testTag("wallet-save")){
                    Text(if(busy)stringResource(R.string.wallet_saving)else stringResource(R.string.wallet_save))
                }
            }
        } }
    }
    if(choosing)AnimatedGlassDialog(true,{choosing=false}) {
        GlassCard { Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.wallet_link_subaccount),style=MaterialTheme.typography.titleLarge)
            TextButton({vm.draft.value=value.copy(boundId=null);choosing=false}){Text(stringResource(R.string.wallet_no_linked_account_2))}
            snapshot?.accounts?.forEach { parent->
                val accounts=snapshot?.cash.orEmpty().filter{it.account_id==parent.id}
                if(accounts.isNotEmpty())Text(parent.name,Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall)
                accounts.forEach{account->TextButton({vm.draft.value=value.copy(boundId=account.id);choosing=false},Modifier.fillMaxWidth()) {
                    Text("${account.name} · ${account.currency.code} · "+if(account.creditProfile!=null)stringResource(R.string.wallet_credit)else stringResource(R.string.wallet_savings))
                }}
            }
        } }
    }
    if(pickingFace) {
        val faces by remember(vm){vm.repository.observeFaces()}.collectAsStateWithLifecycle(null)
        WalletFacePickerDialog(faces,cache,value.image?.key?:value.imageKey,{face->
            vm.draft.value=vm.draft.value?.copy(imageKey=face.key,image=null,faceStyle=null);pickingFace=false
        }){pickingFace=false}
    }
    uri?.let { selected->WalletCrop(selected,{image->vm.draft.value=vm.draft.value?.copy(imageKey=image.key,image=image,faceStyle=null)}){
        runCatching{context.contentResolver.releasePersistableUriPermission(Uri.parse(selected),Intent.FLAG_GRANT_READ_URI_PERMISSION)};uri=null
    } }
}

@Composable internal fun WalletCrop(uri:String,onImage:(WalletImage)->Unit,onClose:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var source by remember(uri){mutableStateOf<Bitmap?>(null)}
    var failed by remember { mutableStateOf(false) };var busy by remember { mutableStateOf(false) }
    var zoom by rememberSaveable{mutableFloatStateOf(1f)}
    var x by rememberSaveable{mutableFloatStateOf(0f)};var y by rememberSaveable{mutableFloatStateOf(0f)}
    // Store the image on the main thread: the effect may resume wherever the decode finished.
    LaunchedEffect(uri){try{val bitmap=withContext(Dispatchers.IO){WalletImageCodec.read(context,Uri.parse(uri))}
        withContext(Dispatchers.Main.immediate){source=bitmap}}
        catch(c:CancellationException){throw c}catch(_:Exception){failed=true}}
    AnimatedGlassDialog(true,{if(!busy)onClose()}) {
        GlassCard { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wallet_adjust_card_face),style=MaterialTheme.typography.titleLarge)
            val bitmap=source
            if(bitmap!=null) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(WalletRules.ASPECT).clip(RoundedCornerShape(18.dp))
                    .testTag("wallet-crop").pointerInput(busy){detectTransformGestures{_,pan,scale,_->if(!busy){
                        zoom=(zoom*scale).coerceIn(1f,5f);x=(x+pan.x/size.width).coerceIn(-3f,3f);y=(y+pan.y/size.height).coerceIn(-3f,3f)
                    }}}) {drawIntoCanvas{it.nativeCanvas.drawBitmap(bitmap,null,WalletImageCodec.cropRect(bitmap.width,bitmap.height,size.width,size.height,zoom,x,y),android.graphics.Paint(3))}}
                Text(stringResource(R.string.wallet_move_pinch_crop_fixed_card),style=MaterialTheme.typography.bodySmall)
                Slider(zoom,{zoom=it},valueRange=1f..5f,enabled=!busy)
                TextButton({scope.launch{busy=true;try{source=withContext(Dispatchers.Default){Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply{postRotate(90f)},true)};zoom=1f;x=0f;y=0f}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("wallet-rotate")){Text(stringResource(R.string.wallet_rotate_90))}
            }else if(!failed)CircularProgressIndicator()
            if(failed)HintMessage(stringResource(R.string.wallet_unable_process_image_use_static),isError=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                TextButton(onClose,enabled=!busy){Text(stringResource(R.string.wallet_cancel))}
                Button({scope.launch{busy=true;failed=false;try{val image=withContext(Dispatchers.Default){WalletImageCodec.encode(requireNotNull(bitmap),zoom,x,y)};onImage(image);onClose()}
                    catch(c:CancellationException){throw c}catch(_:Exception){failed=true}finally{busy=false}}},enabled=bitmap!=null&&!busy,modifier=Modifier.testTag("wallet-crop-confirm")){Text(stringResource(R.string.wallet_use_image))}
            }
        } }
    }
}
