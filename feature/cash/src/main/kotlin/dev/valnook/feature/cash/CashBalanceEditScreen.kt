package dev.valnook.feature.cash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.money.DecimalRules

data class CashBalanceToolbarState(val busy:Boolean,val enabled:Boolean,val label:String,val save:()->Unit)

@Composable fun CashBalanceSaveAction(state:CashBalanceToolbarState?) {
    IconButton({state?.save?.invoke()},enabled=state?.enabled==true,
        modifier=Modifier.testTag("cash-balance-save").semantics{contentDescription=state?.label.orEmpty()}) {
        if(state?.busy==true)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
        else { val color=LocalContentColor.current
            Canvas(Modifier.size(24.dp)) {
                drawLine(color,Offset(size.width*.18f,size.height*.5f),Offset(size.width*.42f,size.height*.74f),2.dp.toPx(),StrokeCap.Round)
                drawLine(color,Offset(size.width*.42f,size.height*.74f),Offset(size.width*.84f,size.height*.25f),2.dp.toPx(),StrokeCap.Round)
            }
        }
    }
}

@Composable fun CashBalanceEditScreen(vm:CashBalanceEditViewModel,onBack:()->Unit,
    onToolbar:(CashBalanceToolbarState?)->Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val keyboard=LocalSoftwareKeyboardController.current;val focus=LocalFocusManager.current
    val preview=vm.changePreview()
    val target=state.account?.let { runCatching { DecimalRules.parse_signed_minor(state.balanceInput,it.currency) }.getOrNull() }
    val busy=submission.phase==SubmissionPhase.WORKING
    val retry=submission.phase==SubmissionPhase.UNKNOWN
    val enabled=state.loaded&&!busy&&submission.phase!=SubmissionPhase.SUCCEEDED&&
        (retry||(target!=null&&target!=state.account?.balance_minor&&preview!=null))
    val label=stringResource(if(retry)R.string.cash_review_retry else R.string.cash_save)
    SideEffect { onToolbar(CashBalanceToolbarState(busy,enabled,label) {focus.clearFocus();keyboard?.hide();vm.submit()}) }
    DisposableEffect(onToolbar){onDispose{onToolbar(null)}}
    LaunchedEffect(submission.phase){if(vm.consumeSuccess())onBack()}
    if(!state.loaded) {
        if(state.failed)Text(stringResource(R.string.cash_account_load_failed))else CircularProgressIndicator()
        return
    }
    val account=requireNotNull(state.account)
    val amountLabel=stringResource(R.string.cash_current_balance)
    val signLabel=stringResource(dev.valnook.core.designsystem.R.string.toggle_sign)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()),
        verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text(account.name,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(vertical=24.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(account.currency.code,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                BasicTextField(state.balanceInput,vm::changeBalance,enabled=submission.editable,singleLine=true,
                    modifier=Modifier.weight(1f).heightIn(min=60.dp).testTag("cash-balance-input").semantics{contentDescription=amountLabel},
                    textStyle=MaterialTheme.typography.displaySmall.copy(fontSize=(40f-(state.balanceInput.length-10).coerceAtLeast(0)*1.5f).coerceAtLeast(24f).sp,
                        color=MaterialTheme.colorScheme.onSurface,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,textAlign=TextAlign.Center,fontFeatureSettings="tnum"),
                    cursorBrush=SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),
                    keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))
                IconButton({vm.changeBalance(if(state.balanceInput.startsWith("-"))state.balanceInput.removePrefix("-")else "-"+state.balanceInput)},
                    enabled=submission.editable,modifier=Modifier.semantics{contentDescription=signLabel}){Text("±")}
            }
        }
        GlassCard { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.cash_previous_balance,DecimalRules.format_display(account.balance_minor,account.currency.fraction_digits)+" "+account.currency.code),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.cash_balance_change,preview?:"—"),style=MaterialTheme.typography.titleMedium)
        } }
        OutlinedTextField(state.entryNote,vm::changeNote,enabled=submission.editable,modifier=Modifier.fillMaxWidth().testTag("cash-balance-note"),
            label={Text(stringResource(R.string.cash_note))},minLines=2,maxLines=5,shape=MaterialTheme.shapes.medium)
        ErrorMessage(submission.error?.name)
        if(retry)HintMessage(stringResource(R.string.cash_review_retry))
    }
}
