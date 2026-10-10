package dev.valnook.feature.wallet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.valnook.designsystem.*
import dev.valnook.domain.model.WalletRules
import dev.valnook.domain.repository.WalletPrivateContent
import dev.valnook.domain.repository.walletCvvValid

private fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable internal fun WalletPrivateProtection(state: WalletPrivateState) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val window=LocalContext.current.activity()?.window
    DisposableEffect(state,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)state.conceal() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);state.conceal() }
    }
    val sensitive=state.sensitive
    DisposableEffect(window,sensitive) {
        val alreadySecure=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
        if(sensitive) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if(sensitive&&!alreadySecure)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private val PrivateDialogProperties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn)

@Composable internal fun WalletPrivateDialogs(state:WalletPrivateState) {
    val maximumHeight=with(LocalDensity.current){LocalWindowInfo.current.containerSize.height.toDp()}*.8f
    // wording(en, zh): edit both translations together. Keep the encryption algorithm and exclusions aligned
    // with docs/PRIVACY_POLICY_{CN,EN}.md and feature/settings/src/main/res/raw*/privacy_policy.txt.
    // 英文在前、中文在后。风险提示保留 Keystore / AES-GCM，不写“及背面、侧面配色”；
    // 配色仍不备份。MIT 免责声明两种语言都必须加粗；“不再显示”只影响本机提示。
    if(state.warning) {
        var skip by remember { mutableStateOf(false) }
        AnimatedGlassDialog(true,state::cancelWarning,properties=PrivateDialogProperties) {
            GlassCard { Column(Modifier.heightIn(max=maximumHeight)
                .verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(wording("Private card details","卡片背面隐私提示"),style=MaterialTheme.typography.titleLarge)
                Text(wording("Card numbers, expiry dates, CVV1 and CVV2 are sensitive. Disclosure may cause financial loss.",
                    "卡号、有效期、CVV1 和 CVV2 属于敏感信息，泄露可能造成财产损失。"))
                Text(wording("Details entered here are encrypted only on this device, using Android Keystore to manage keys and AES-GCM to encrypt content. They are not uploaded, included in local or OneDrive cloud backups or file exports, or accessible through web management.",
                    "您填写的背面内容仅加密保存在本设备，使用 Android Keystore 管理密钥，并通过 AES-GCM 加密内容。不会上传至服务器，不会纳入本地备份、OneDrive 等云备份或其他文件导出，网页管理也无法访问。"))
                Text(wording("Uninstalling, clearing app data or successfully replacing data from a backup makes these details unrecoverable. Enable your device's app lock for Valnook where available.",
                    "卸载应用、清除应用数据或成功覆盖恢复备份后，这部分内容将无法恢复。建议在设备支持时，为 Valnook 启用系统应用锁。"))
                Text(wording("Copying a field places it on the system clipboard, outside this app's encrypted storage. Use this action with care.",
                    "主动复制会将对应内容写入系统剪贴板，不受本地加密存储保护，请谨慎使用。"))
                Text(wording("Valnook is open-source software distributed under the MIT License and accepts no liability for any financial loss you incur.",
                    "Valnook为按照MIT协议发行的开源软件，不对您的财产损失负任何责任"),fontWeight=FontWeight.Bold)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(state::cancelWarning){Text(wording("Cancel","取消"))}
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(skip,onValueChange={skip=it})
                        .testTag("wallet-private-warning-skip"),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(skip,null)
                        Text(wording("Don't show again","不再显示"),style=MaterialTheme.typography.labelMedium)
                    }
                    TextButton({state.confirmWarning(skip)},Modifier.testTag("wallet-private-warning-confirm")){Text(wording("OK","确定"))}
                }
            } }
        }
    }
    state.draft?.let { value -> PrivateEditor(state,value) }
    if(state.discard) AnimatedGlassDialog(true,{state.discard=false},properties=PrivateDialogProperties) {
        GlassCard { Column(Modifier.padding(22.dp)) {
            Text(wording("Discard unsaved changes?","放弃未保存的修改？"),style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({state.discard=false}){Text(wording("Keep editing","继续编辑"))}
                TextButton(state::discardEdit,Modifier.testTag("wallet-private-discard")){Text(wording("Discard","放弃"))}
            }
        } }
    }
    if(state.error) AnimatedGlassDialog(true,{state.error=false},properties=PrivateDialogProperties) {
        GlassCard { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(wording("Private details unavailable","私密内容暂不可用"),style=MaterialTheme.typography.titleMedium)
            Text(wording("Could not securely read or save these details. Retry after closing the editor. If the local key or encrypted file is lost, backups cannot recover it.",
                "无法安全读取或保存这些内容，请关闭编辑后重试。如果本地密钥或加密文件丢失，备份无法恢复这部分内容。"))
            TextButton({state.error=false},Modifier.align(Alignment.End)){Text(wording("OK","知道了"))}
        } }
    }
}

@Composable private fun PrivateEditor(state:WalletPrivateState,value:WalletPrivateContent) {
    // Labels/helpers are bilingual; entered values and HEX codes are locale-independent.
    // 文案需中英同步；卡号中的符号、前导零和颜色值不能随语言转换。
    var palette by remember { mutableStateOf(false) }
    var tooLong by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val editLabel=wording("Card back colors","卡片背面配色")
    val maximumHeight=with(LocalDensity.current){LocalWindowInfo.current.containerSize.height.toDp()}*.85f
    AnimatedGlassDialog(true,state::cancelEdit,properties=PrivateDialogProperties) {
        GlassCard { Column(Modifier.heightIn(max=maximumHeight)
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(wording("Private card details","编辑卡片背面"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton({focus.clearFocus();keyboard?.hide();palette=!palette},Modifier.testTag("wallet-private-palette")
                    .semantics{contentDescription=editLabel},enabled=!state.saving){WalletPaletteIcon()}
            }
            WalletCardBack(value,Modifier.fillMaxWidth().aspectRatio(WalletRules.ASPECT).clip(RoundedCornerShape(18.dp)))
            if(palette) PrivatePalette(value,state::updateDraft,!state.saving)
            OutlinedTextField(value.number,{text->
                val cleaned=text.filterNot{it.isISOControl()}
                tooLong=cleaned.codePointCount(0,cleaned.length)>38
                if(!tooLong)state.updateDraft(value.copy(number=cleaned))
            },Modifier.fillMaxWidth().testTag("wallet-private-number"),label={Text(wording("Card number","卡号"))},
                singleLine=true,enabled=!state.saving,isError=tooLong,
                supportingText={Text(if(tooLong)wording("Up to 38 characters","最多填写 38 个字符")else wording("Up to 38 characters · symbols allowed","最多 38 个字符，可使用 *、- 等符号"))})
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value.showNumberSpacing,enabled=!state.saving,role=Role.Switch,
                    onValueChange={state.updateDraft(value.copy(showNumberSpacing=it))})
                .testTag("wallet-private-spacing").padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(wording("Show card number spacing","显示卡号间隔"),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(value.showNumberSpacing,null,enabled=!state.saving)
            }
            PrivateInput(wording("Expiry · MM/YY","有效期 · MM/YY"),value.expiry,"wallet-private-expiry",!state.saving){state.updateDraft(value.copy(expiry=it))}
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)){PrivateInput("CVV1",value.cvv1,"wallet-private-cvv1",!state.saving,cvv=true){state.updateDraft(value.copy(cvv1=it))}}
                Box(Modifier.weight(1f)){PrivateInput("CVV2",value.cvv2,"wallet-private-cvv2",!state.saving,cvv=true){state.updateDraft(value.copy(cvv2=it))}}
            }
            Text(wording("Encrypted on this device only · excluded from backups and file exports","仅本机加密保存 · 不纳入备份或文件导出"),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(state::cancelEdit,enabled=!state.saving,modifier=Modifier.testTag("wallet-private-cancel")){Text(wording("Cancel","取消"))}
                TextButton({focus.clearFocus();keyboard?.hide();state.save()},enabled=!state.saving&&!tooLong&&walletCvvValid(value.cvv1)&&walletCvvValid(value.cvv2),
                    modifier=Modifier.testTag("wallet-private-save")){Text(if(state.saving)wording("Saving…","保存中…")else wording("Save","保存"))}
            }
        } }
    }
}

@Composable private fun PrivateInput(label:String,value:String,tag:String,enabled:Boolean,cvv:Boolean=false,change:(String)->Unit) {
    val invalid=cvv&&!walletCvvValid(value)
    OutlinedTextField(value,{
        if(cvv) { if(walletCvvValid(it))change(it) }
        else if(it.length<=16)change(it.filterNot(Char::isISOControl))
    },Modifier.fillMaxWidth().testTag(tag),label={Text(label)},singleLine=true,enabled=enabled,
        keyboardOptions=KeyboardOptions(keyboardType=if(cvv)KeyboardType.NumberPassword else KeyboardType.Text),
        isError=invalid,supportingText=if(cvv){{Text(wording("Up to 4 digits · optional","最多 4 位数字，可留空"))}}else null)
}

@Composable private fun PrivatePalette(value:WalletPrivateContent,change:(WalletPrivateContent)->Unit,enabled:Boolean) {
    var edge by remember { mutableStateOf(false) }
    val current=if(edge)value.edgeColor else value.backColor
    var hex by remember(current,edge) { mutableStateOf("%06X".format(current and 0xffffff)) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!edge,{edge=false},label={Text(wording("Back","背面"))})
        FilterChip(edge,{edge=true},label={Text(wording("Edge","侧面"))})
    }
    fun pick(color:Long){change(if(edge)value.copy(edgeColor=color)else value.copy(backColor=color))}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        listOf(0xff41464fL,0xff181b20L,0xff858b94L,0xffd8d6d0L,0xff485b6cL,0xff715b64L).forEach { color ->
            val description=wording("Select color","选择颜色")+" %06X".format(color and 0xffffff)
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(color.toInt()))
                .then(if(color==current)Modifier.border(2.dp,MaterialTheme.colorScheme.onSurface,CircleShape)else Modifier)
                .clickable(enabled=enabled){pick(color)}.semantics{contentDescription=description;selected=color==current})
        }
    }
    OutlinedTextField(hex,{text->
        val cleaned=text.removePrefix("#").uppercase()
        if(cleaned.length<=6&&cleaned.all{it in '0'..'9'||it in 'A'..'F'}) {
            hex=cleaned
            if(cleaned.length==6)pick(0xff000000L or cleaned.toLong(16))
        }
    },Modifier.fillMaxWidth().testTag("wallet-private-color"),label={Text(wording("Custom color · HEX","自定义颜色 · HEX"))},prefix={Text("#")},
        singleLine=true,enabled=enabled,supportingText={Text(wording("Six hexadecimal digits","填写六位十六进制颜色值"))})
}
