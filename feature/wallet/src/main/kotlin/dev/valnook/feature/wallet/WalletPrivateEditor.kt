package dev.valnook.feature.wallet

import androidx.compose.ui.res.stringResource
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
import dev.valnook.domain.repository.WALLET_HOLDER_MAX
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
    // The text is in res/values and res/values-b+zh+Hans (wallet_*): edit both translations together. Keep the encryption
    // algorithm and exclusions aligned with docs/PRIVACY_POLICY_{CN,EN}.md and feature/settings/src/main/res/raw*/privacy_policy.txt.
    // 英文在 values、中文在 values-b+zh+Hans。风险提示保留 Keystore / AES-GCM，不写“及背面、侧面配色”；
    // 配色仍不备份。免责声明两种语言都必须加粗，且与 PrivacyPolicyScreen 加粗的句子一致；“不再显示”只影响本机提示。
    if(state.warning) {
        var skip by remember { mutableStateOf(false) }
        AnimatedGlassDialog(true,state::cancelWarning,properties=PrivateDialogProperties) {
            GlassCard { Column(Modifier.heightIn(max=maximumHeight)
                .verticalScroll(rememberScrollState()).padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.wallet_private_card_details),style=MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.wallet_card_numbers_cardholder_names_expiry))
                Text(stringResource(R.string.wallet_details_entered_here_are_encrypted))
                Text(stringResource(R.string.wallet_uninstalling_clearing_app_data_successfully))
                Text(stringResource(R.string.wallet_copying_field_places_system_clipboard))
                Text(stringResource(R.string.wallet_fullest_extent_permitted_by_applicable),fontWeight=FontWeight.Bold)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(state::cancelWarning){Text(stringResource(R.string.wallet_cancel))}
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(skip,onValueChange={skip=it})
                        .testTag("wallet-private-warning-skip"),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(skip,null)
                        Text(stringResource(R.string.wallet_don_t_show_again),style=MaterialTheme.typography.labelMedium)
                    }
                    TextButton({state.confirmWarning(skip)},Modifier.testTag("wallet-private-warning-confirm")){Text(stringResource(R.string.wallet_ok))}
                }
            } }
        }
    }
    state.draft?.let { value -> PrivateEditor(state,value) }
    if(state.discard) AnimatedGlassDialog(true,{state.discard=false},properties=PrivateDialogProperties) {
        GlassCard { Column(Modifier.padding(22.dp)) {
            Text(stringResource(R.string.wallet_discard_unsaved_changes),style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({state.discard=false}){Text(stringResource(R.string.wallet_keep_editing))}
                TextButton(state::discardEdit,Modifier.testTag("wallet-private-discard")){Text(stringResource(R.string.wallet_discard))}
            }
        } }
    }
    if(state.error) AnimatedGlassDialog(true,{state.error=false},properties=PrivateDialogProperties) {
        GlassCard { Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wallet_private_details_unavailable),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.wallet_could_not_securely_read_save))
            TextButton({state.error=false},Modifier.align(Alignment.End)){Text(stringResource(R.string.wallet_ok_2))}
        } }
    }
}

@Composable private fun PrivateEditor(state:WalletPrivateState,value:WalletPrivateContent) {
    // Labels/helpers are bilingual; entered values and HEX codes are locale-independent.
    // 文案需中英同步；卡号中的符号、前导零和颜色值不能随语言转换。
    var palette by remember { mutableStateOf(false) }
    var tooLong by remember { mutableStateOf(false) }
    val editLabel=stringResource(R.string.wallet_card_back_colors)
    val maximumHeight=with(LocalDensity.current){LocalWindowInfo.current.containerSize.height.toDp()}*.85f
    AnimatedGlassDialog(true,state::cancelEdit,properties=PrivateDialogProperties) {
        // The dialog is its own window: only its focus manager and keyboard controller reach the fields.
        val keyboard=LocalSoftwareKeyboardController.current
        val focus=LocalFocusManager.current
        GlassCard { Column(Modifier.heightIn(max=maximumHeight)
            .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.wallet_private_card_details_2),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton({focus.clearFocus();keyboard?.hide();palette=!palette},Modifier.testTag("wallet-private-palette")
                    .semantics{contentDescription=editLabel},enabled=!state.saving){WalletPaletteIcon()}
            }
            WalletCardBack(value,Modifier.fillMaxWidth().aspectRatio(WalletRules.ASPECT).clip(RoundedCornerShape(18.dp)))
            if(palette) PrivatePalette(value,state::updateDraft,!state.saving)
            OutlinedTextField(value.number,{text->
                val cleaned=text.filterNot{it.isISOControl()}
                tooLong=cleaned.codePointCount(0,cleaned.length)>38
                if(!tooLong)state.updateDraft(value.copy(number=cleaned))
            },Modifier.fillMaxWidth().testTag("wallet-private-number"),label={Text(stringResource(R.string.wallet_card_number))},
                singleLine=true,enabled=!state.saving,isError=tooLong,
                supportingText={Text(if(tooLong)stringResource(R.string.wallet_up_38_characters)else stringResource(R.string.wallet_up_38_characters_symbols_allowed))})
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value.showNumberSpacing,enabled=!state.saving,role=Role.Switch,
                    onValueChange={state.updateDraft(value.copy(showNumberSpacing=it))})
                .testTag("wallet-private-spacing").padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.wallet_show_card_number_spacing),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(value.showNumberSpacing,null,enabled=!state.saving)
            }
            PrivateInput(stringResource(R.string.wallet_expiry_mm_yy),value.expiry,"wallet-private-expiry",!state.saving){state.updateDraft(value.copy(expiry=it))}
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)){PrivateInput("CVV1",value.cvv1,"wallet-private-cvv1",!state.saving,cvv=true){state.updateDraft(value.copy(cvv1=it))}}
                Box(Modifier.weight(1f)){PrivateInput("CVV2",value.cvv2,"wallet-private-cvv2",!state.saving,cvv=true){state.updateDraft(value.copy(cvv2=it))}}
            }
            PrivateInput(stringResource(R.string.wallet_cardholder),value.holder,"wallet-private-holder",!state.saving,
                maxCodePoints=WALLET_HOLDER_MAX){state.updateDraft(value.copy(holder=it))}
            Text(stringResource(R.string.wallet_encrypted_device_only_excluded_from),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(state::cancelEdit,enabled=!state.saving,modifier=Modifier.testTag("wallet-private-cancel")){Text(stringResource(R.string.wallet_cancel))}
                TextButton({focus.clearFocus();keyboard?.hide();state.save()},enabled=!state.saving&&!tooLong&&walletCvvValid(value.cvv1)&&walletCvvValid(value.cvv2),
                    modifier=Modifier.testTag("wallet-private-save")){Text(if(state.saving)stringResource(R.string.wallet_saving)else stringResource(R.string.wallet_save))}
            }
        } }
    }
}

/** [maxCodePoints] counts characters as WalletPrivateContent.validated does; other text fields keep the 16-unit limit. */
@Composable private fun PrivateInput(label:String,value:String,tag:String,enabled:Boolean,cvv:Boolean=false,
    maxCodePoints:Int?=null,change:(String)->Unit) {
    val invalid=cvv&&!walletCvvValid(value)
    OutlinedTextField(value,{
        val cleaned=it.filterNot(Char::isISOControl)
        if(cvv) { if(walletCvvValid(it))change(it) }
        else if(maxCodePoints!=null) { if(cleaned.codePointCount(0,cleaned.length)<=maxCodePoints)change(cleaned) }
        else if(it.length<=16)change(cleaned)
    },Modifier.fillMaxWidth().testTag(tag),label={Text(label)},singleLine=true,enabled=enabled,
        keyboardOptions=KeyboardOptions(keyboardType=if(cvv)KeyboardType.NumberPassword else KeyboardType.Text),
        isError=invalid,supportingText=when {
            cvv -> {{Text(stringResource(R.string.wallet_up_4_digits_optional))}}
            maxCodePoints!=null -> {{Text(stringResource(R.string.wallet_up_characters_optional, maxCodePoints))}}
            else -> null
        })
}

@Composable private fun PrivatePalette(value:WalletPrivateContent,change:(WalletPrivateContent)->Unit,enabled:Boolean) {
    var edge by remember { mutableStateOf(false) }
    val current=if(edge)value.edgeColor else value.backColor
    var hex by remember(current,edge) { mutableStateOf("%06X".format(current and 0xffffff)) }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(!edge,{edge=false},label={Text(stringResource(R.string.wallet_back))})
        FilterChip(edge,{edge=true},label={Text(stringResource(R.string.wallet_edge))})
    }
    fun pick(color:Long){change(if(edge)value.copy(edgeColor=color)else value.copy(backColor=color))}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        listOf(0xff41464fL,0xff181b20L,0xff858b94L,0xffd8d6d0L,0xff485b6cL,0xff715b64L).forEach { color ->
            val description=stringResource(R.string.wallet_select_color)+" %06X".format(color and 0xffffff)
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
    },Modifier.fillMaxWidth().testTag("wallet-private-color"),label={Text(stringResource(R.string.wallet_custom_color_hex))},prefix={Text("#")},
        singleLine=true,enabled=enabled,supportingText={Text(stringResource(R.string.wallet_six_hexadecimal_digits))})
}
