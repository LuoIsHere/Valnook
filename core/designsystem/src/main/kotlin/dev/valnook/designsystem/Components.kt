package dev.valnook.designsystem

import android.app.DatePickerDialog as NativeDatePickerDialog
import android.app.TimePickerDialog as NativeTimePickerDialog
import android.content.DialogInterface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.valnook.core.designsystem.R
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale


@Composable fun ErrorMessage(code:String?) {
    if(code==null) return
    val resource=when(code) {
        "NOT_FOUND"->R.string.error_not_found
        "INSUFFICIENT_CASH"->R.string.error_cash
        "INSUFFICIENT_HOLDING"->R.string.error_holding
        "STALE_BALANCE"->R.string.error_stale
        "STALE_RECORD"->R.string.error_record_stale
        "SOURCE_RECORD"->R.string.error_source_record
        "OPERATION_CONFLICT"->R.string.error_conflict
        "ALREADY_CLOSED"->R.string.error_closed
        "NOT_MATURED"->R.string.error_maturity
        "PRECISION"->R.string.error_precision
        "OVERFLOW"->R.string.error_overflow
        "NAME"->R.string.error_name
        "DUPLICATE_TYPE"->R.string.error_type
        "AMOUNT_TOO_SMALL"->R.string.error_small
        "DATE"->R.string.error_date
        "POSITIVE"->R.string.error_positive
        "STORAGE"->R.string.error_storage
        "INVALID_ACCOUNT_TYPE"->R.string.error_account_type
        "INVALID_CREDIT_LIMIT"->R.string.error_credit_limit
        "INVALID_STATEMENT_DAY"->R.string.error_statement_day
        "INVALID_DUE_RULE"->R.string.error_due_rule
        "CREDIT_SOURCE_INVALID"->R.string.error_credit_source
        "CREDIT_SOURCE_CURRENCY"->R.string.error_credit_source_currency
        "CREDIT_SOURCE_PARENT"->R.string.error_credit_source_parent
        "CREDIT_SOURCE_CHAIN", "CREDIT_SOURCE_CYCLE"->R.string.error_credit_source_chain
        "CREDIT_LIMIT_IN_USE"->R.string.error_credit_limit_in_use
        "BALANCE_ACCOUNT_IN_USE"->R.string.error_balance_account_in_use
        else->R.string.error_format
    }
    Text(stringResource(resource),color=MaterialTheme.colorScheme.error,
        style=MaterialTheme.typography.bodyMedium,
        modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
}
@Composable fun AmountText(amount:String,currency:String) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(amount,style=MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings="tnum"))
        Text(currency,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun EmptyState(message:String) {
    Text(message,modifier=Modifier.fillMaxWidth().padding(vertical=Space.xl),
        style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun ActionButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    destructive:Boolean=false,content:@Composable RowScope.()->Unit) {
    val colors=MaterialTheme.colorScheme
    OutlinedButton(onClick=onClick,modifier=modifier.heightIn(min=48.dp),enabled=enabled,
        shape=RoundedCornerShape(12.dp),
        border=BorderStroke(1.dp,if(destructive)colors.error else colors.outlineVariant),
        colors=ButtonDefaults.outlinedButtonColors(
            containerColor=if(destructive)colors.errorContainer else colors.surfaceContainerLow,
            contentColor=if(destructive)colors.onErrorContainer else colors.primary),
        contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),content=content)
}
@Composable fun Field(label:String,value:String,on_change:(String)->Unit,numeric:Boolean=false,enabled:Boolean=true,
    signed:Boolean=false) {
    val minimum_height=(64f+32f*(LocalDensity.current.fontScale.coerceAtLeast(1f)-1f)).dp
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val toggle_sign_description=if(numeric&&signed) stringResource(R.string.toggle_sign) else ""
    Box(Modifier.fillMaxWidth().testTag("input-$label")) {
    OutlinedTextField(value=value,onValueChange=on_change,label={Text(label)},singleLine=true,
        textStyle=MaterialTheme.typography.bodyLarge,shape=RoundedCornerShape(12.dp),
        enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=minimum_height),
        trailingIcon=if(numeric&&signed) {{
            IconButton(onClick={
                val next=when {
                    value.startsWith("-")->value.removePrefix("-")
                    value.isBlank()->"-"
                    else->"-$value"
                }
                on_change(next)
            },enabled=enabled) { Text("±",modifier=Modifier.semantics {
                contentDescription=toggle_sign_description
            }) }
        }} else null,
        keyboardOptions=KeyboardOptions(keyboardType=if(numeric) KeyboardType.Decimal else KeyboardType.Text,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}))
    }
}
/** Selection and numeric inputs share the same outline, label baseline and width. */
@Composable fun SelectorField(label:String,value:String,on_select:()->Unit,enabled:Boolean=true) {
    val minimum_height=(64f+32f*(LocalDensity.current.fontScale.coerceAtLeast(1f)-1f)).dp
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val arrow_color=MaterialTheme.colorScheme.onSurfaceVariant
    val shape=RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().testTag("input-$label")) {
        OutlinedTextField(value=value,onValueChange={},readOnly=true,label={Text(label)},
            singleLine=true,textStyle=MaterialTheme.typography.bodyLarge,shape=shape,
            enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=minimum_height),
            trailingIcon={Canvas(Modifier.size(18.dp)) {
                val stroke=2.dp.toPx()
                drawLine(arrow_color,Offset(size.width*0.2f,size.height*0.4f),Offset(size.width*0.5f,size.height*0.7f),stroke,StrokeCap.Round)
                drawLine(arrow_color,Offset(size.width*0.5f,size.height*0.7f),Offset(size.width*0.8f,size.height*0.4f),stroke,StrokeCap.Round)
            }},
            colors=OutlinedTextFieldDefaults.colors(
                disabledTextColor=MaterialTheme.colorScheme.onSurface,
                disabledLabelColor=MaterialTheme.colorScheme.onSurfaceVariant))
        Box(Modifier.matchParentSize().clip(shape).clickable(enabled=enabled,role=Role.Button,onClick={focus.clearFocus();keyboard?.hide();on_select()})
            .semantics {contentDescription="$label: $value"})
    }
}
@Composable fun ChoiceField(label:String,selected:String,options:List<Pair<String,String>>,
    on_change:(String)->Unit,enabled:Boolean=true) {
    var expanded by remember {mutableStateOf(false)}
    Box(Modifier.fillMaxWidth()) {
        SelectorField(label,options.firstOrNull{it.first==selected}?.second.orEmpty(),{expanded=true},enabled)
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            options.forEach{(key,name)->DropdownMenuItem(text={Text(name)},onClick={on_change(key);expanded=false})}
        }
    }
}
@Composable fun CurrencyChoice(selected:String,on_change:(String)->Unit,enabled:Boolean=true,
    options:List<Pair<String,String>>,excluded:Set<String> = emptySet()) {
    var expanded by rememberSaveable {mutableStateOf(false)}
    var query by rememberSaveable {mutableStateOf("")}
    val pickerRates=LocalCurrencyPickerRates.current
    SelectorField(stringResource(R.string.currency),selected,
        {query="";expanded=true},enabled)
    if(expanded) Dialog(onDismissRequest={expanded=false},properties=DialogProperties(decorFitsSystemWindows=false)) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp)) {
            Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(Space.md),
                    verticalArrangement=Arrangement.spacedBy(Space.md)) {
                    Text(stringResource(R.string.choose_currency),style=MaterialTheme.typography.titleLarge)
                    Text(pickerRates.baseCode?.let { stringResource(R.string.currency_rate_to, it) }
                        ?: stringResource(R.string.currency_rate_no_base),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Field(stringResource(R.string.search_currency),query,{query=it})
                    val matches=options.filter{it.first !in excluded &&
                        (it.first.contains(query.trim(),true)||it.second.contains(query.trim(),true))}
                    LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth().testTag("currency-list")) {
                        if(matches.isEmpty())item{EmptyState(stringResource(R.string.no_currency_match))}
                        items(matches,key={it.first}){(code,name)->
                            Row(Modifier.fillMaxWidth().clickable {on_change(code);expanded=false}.padding(vertical=8.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Space.md)) {
                                Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainer,
                                    modifier=Modifier.width(56.dp)) {
                                    Text(code,Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.labelLarge,
                                        textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                                }
                                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                val configured=if(code==pickerRates.baseCode) "1" else pickerRates.rates[code]
                                Column(Modifier.widthIn(max=96.dp),horizontalAlignment=Alignment.End) {
                                    Text(configured ?: "1",Modifier.testTag("currency-rate-$code"),
                                        style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                                    if(configured==null)Text(stringResource(R.string.currency_rate_default),style=MaterialTheme.typography.bodySmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if(code==selected)Text("✓",color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    TextButton(onClick={expanded=false},modifier=Modifier.align(Alignment.End)){Text(stringResource(R.string.cancel))}
                }
            }
        }
    }
}
@Composable fun DateField(label:String,value:String,on_change:(String)->Unit,enabled:Boolean=true) {
    var open by rememberSaveable {mutableStateOf(false)}
    val context=LocalContext.current
    val confirm=stringResource(R.string.confirm)
    val cancel=stringResource(R.string.cancel)
    SelectorField(label,value,{open=true},enabled)
    if(open) DisposableEffect(context,confirm,cancel) {
        val date=runCatching{LocalDate.parse(value)}.getOrElse{LocalDate.now()}
        val dialog=NativeDatePickerDialog(context,{_,year,month,day->
            on_change(LocalDate.of(year,month+1,day).toString());open=false
        },date.year,date.monthValue-1,date.dayOfMonth)
        dialog.setOnDismissListener {open=false}
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).text=confirm
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text=cancel
        onDispose {dialog.setOnDismissListener(null);dialog.dismiss()}
    }
}
@Composable fun TimeField(label:String,value:String,on_change:(String)->Unit,enabled:Boolean=true) {
    var open by rememberSaveable {mutableStateOf(false)}
    val context=LocalContext.current
    val confirm=stringResource(R.string.confirm)
    val cancel=stringResource(R.string.cancel)
    SelectorField(label,value,{open=true},enabled)
    if(open) DisposableEffect(context,confirm,cancel) {
        val time=runCatching{LocalTime.parse(value)}.getOrElse{LocalTime.now()}
        val dialog=NativeTimePickerDialog(context,{_,hour,minute->
            on_change(String.format(Locale.ROOT,"%02d:%02d",hour,minute));open=false
        },time.hour,time.minute,true)
        dialog.setOnDismissListener {open=false}
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).text=confirm
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text=cancel
        onDispose {dialog.setOnDismissListener(null);dialog.dismiss()}
    }
}
@Composable fun CashLinkOption(checked:Boolean,on_change:(Boolean)->Unit,account:String,currency:String,change:String,enabled:Boolean=true,label:String?=null) {
    OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(Space.sm)) {
                Text(label ?: stringResource(R.string.cash_link),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
                Checkbox(checked=checked,onCheckedChange=on_change,enabled=enabled)
            }
            Text(stringResource(if(checked) R.string.link_effect else R.string.no_link_effect,account,currency,change),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
