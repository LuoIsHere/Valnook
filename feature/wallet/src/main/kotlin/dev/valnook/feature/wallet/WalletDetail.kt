package dev.valnook.feature.wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.feature.cash.CashBalanceSummary
import dev.valnook.feature.cash.CashEntryItem
import java.time.*

@Composable internal fun WalletDetail(vm:WalletViewModel,card:WalletCard,cache:WalletImageCache,
    onEntry:(Long,Long,Long)->Unit,onBalance:(Long,Long)->Unit,edit:()->Unit,
    onTitle:(String?)->Unit,onScrolled:(Boolean)->Unit,cardModifier:Modifier,frame:()->WalletSceneFrame,
    onReturn:()->Unit,returnEnabled:Boolean,returning:Boolean,progress:()->Float) {
    key(card.id,card.boundCashAccountId) {
        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
        val ledger by vm.ledger.collectAsStateWithLifecycle()
        val today by vm.today.collectAsStateWithLifecycle()
        val bounds by vm.bounds.collectAsStateWithLifecycle()
        val account=snapshot?.cash?.firstOrNull{it.id==card.boundCashAccountId}
        val parent=snapshot?.accounts?.firstOrNull{it.id==account?.account_id}
        val names=snapshot?.cash.orEmpty().associate { cash->cash.id to listOf(snapshot?.accounts?.firstOrNull{it.id==cash.account_id}?.name,cash.name).filterNotNull().joinToString(" · ") }
        var collapse by rememberSaveable { mutableFloatStateOf(0f) }
        val list=rememberLazyListState()
        var chooseMonth by rememberSaveable { mutableStateOf(false) }
        val localTitle=if(collapse>.5f)account?.name?:card.name else card.name
        val scrolled by remember{derivedStateOf{collapse>0f||list.firstVisibleItemIndex>0||list.firstVisibleItemScrollOffset>0}}
        SideEffect{onTitle(localTitle);onScrolled(scrolled)}
        DisposableEffect(card.id){onDispose{onTitle(null);onScrolled(false)}}
        BoxWithConstraints(Modifier.fillMaxSize().testTag("wallet-detail").pointerInput(returnEnabled){
            if(!returnEnabled)awaitPointerEventScope {
                while(true)awaitPointerEvent(PointerEventPass.Initial).changes.forEach{it.consume()}
            }
        }) {
            val viewportHeight=with(LocalDensity.current){maxHeight.roundToPx()}
            val panelMotion=Modifier.graphicsLayer{val motion=frame();translationY=viewportHeight*motion.panelOffset}
            val width=(maxWidth-48.dp).coerceAtMost(440.dp).coerceAtMost(maxHeight*.7f)
            val headerHeight=width/WalletRules.ASPECT+24.dp
            val headerPx=with(LocalDensity.current){headerHeight.toPx()}
            val nested=remember(headerPx){object:NestedScrollConnection {
                override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
                    if(available.y>=0)return Offset.Zero
                    val old=collapse;collapse=(collapse-available.y/headerPx).coerceIn(0f,1f)
                    return Offset(0f,-(collapse-old)*headerPx)
                }
                override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
                    if(available.y<=0)return Offset.Zero
                    val old=collapse;collapse=(collapse-available.y/headerPx).coerceIn(0f,1f)
                    return Offset(0f,(old-collapse)*headerPx)
                }
            }}
            var shownMonth by rememberSaveable { mutableStateOf(ledger.month.toString()) }
            LaunchedEffect(ledger.month){if(shownMonth!=ledger.month.toString()){list.scrollToItem(0);shownMonth=ledger.month.toString()}}
            Column(Modifier.fillMaxSize().nestedScroll(nested).padding(top=LocalPageTopSpace.current,bottom=LocalPageBottomSpace.current)) {
                Box(Modifier.fillMaxWidth().height(headerHeight*(1-collapse)).clipToBounds(),contentAlignment=Alignment.TopCenter) {
                    WalletCardView(card,cache,cardModifier.width(width).requiredHeight(width/WalletRules.ASPECT).graphicsLayer {
                        translationY=-headerPx*collapse*.25f
                        val visible=(1-collapse*1.25f).coerceIn(0f,1f)
                        alpha=if(returning)maxOf(visible,(progress()*4).coerceIn(0f,1f))else visible
                    }.semantics{contentDescription=card.name}.clickable(interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()},
                        indication=null,enabled=returnEnabled&&collapse<.8f,onClickLabel=wording("Back to wallet","返回卡包"),onClick=onReturn))
                }
                GlassSurface(Modifier.fillMaxWidth().weight(1f,fill=false).padding(horizontal=16.dp).then(panelMotion),RoundedCornerShape(24.dp)) {
                    LazyColumn(Modifier.fillMaxWidth().testTag("wallet-ledger"),state=list,
                        contentPadding=PaddingValues(start=18.dp,end=18.dp,top=16.dp,bottom=24.dp)) {
                        if(card.boundCashAccountId==null) {
                            item("unbound") {
                                Text(if(card.bindingLost)wording("The linked account was deleted.","原绑定子账户已删除。") else wording("No linked account","尚未绑定子账户"),style=MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp));Text(wording("Keep this card as a collection, or link an account to see its balance and activity.","可以仅收藏卡面，也可以绑定子账户查看余额和资金变动。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(edit,Modifier.padding(top=20.dp)){Text(wording("Link account","绑定子账户"))}
                            }
                        } else if(account==null) item { CircularProgressIndicator(Modifier.size(22.dp)) }
                        else {
                            item("summary") {
                                Text(parent?.name.orEmpty(),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                CashBalanceSummary(account,{onBalance(account.account_id,account.id)},Modifier.padding(vertical=12.dp),snapshot?.cash.orEmpty(),names)
                            }
                            item("month") {
                                Text(wording("Funds activity","资金变动"),Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleLarge)
                                val current=YearMonth.from(today)
                                val first=bounds.firstMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
                                val last=bounds.lastMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
                                val minimum=minOf(first,current);val maximum=maxOf(last,current)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    TextButton({vm.month(maxOf(ledger.month.minusMonths(1),minimum))},enabled=ledger.month>minimum,modifier=Modifier.testTag("wallet-month-previous")){Text("‹")}
                                    TextButton({chooseMonth=true},Modifier.testTag("wallet-month")){Text(ledger.month.toString())}
                                    TextButton({vm.month(minOf(ledger.month.plusMonths(1),maximum))},enabled=ledger.month<maximum,modifier=Modifier.testTag("wallet-month-next")){Text("›")}
                                }
                            }
                            if(ledger.loading && ledger.rows.isEmpty())item("loading"){Box(Modifier.fillMaxWidth().height(80.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(22.dp))}}
                            else if(ledger.failed)item("error"){TextButton(vm::retry){Text(wording("Could not load this month. Retry","本月记录加载失败，点击重试"))}}
                            else if(ledger.rows.isEmpty())item("empty"){Text(wording("No activity this month","本月暂无资金变动"),Modifier.padding(vertical=28.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            itemsIndexed(ledger.rows,key={_,row->row.id}){index,row->
                                val date=Instant.ofEpochMilli(row.occurred_at_ms).atZone(vm.zone).toLocalDate()
                                val previous=ledger.rows.getOrNull(index-1)?.let{Instant.ofEpochMilli(it.occurred_at_ms).atZone(vm.zone).toLocalDate()}
                                if(date!=previous)Text(date.toString(),Modifier.padding(top=16.dp,bottom=6.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                CashEntryItem(row,index<ledger.rows.lastIndex){onEntry(account.account_id,account.id,row.id)}
                            }
                            if(ledger.more)item("more") {
                                if(ledger.appendFailed)TextButton(vm::more){Text(wording("Retry loading more","继续加载失败，点击重试"))}
                                else {LaunchedEffect(ledger.rows.size,ledger.month){vm.more()};Box(Modifier.fillMaxWidth().height(40.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(18.dp))}}
                            }
                        }
                    }
                }
            }
        }
        if(chooseMonth) {
            val current=YearMonth.from(today)
            val first=bounds.firstMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
            val last=bounds.lastMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
            WalletMonthPicker(ledger.month,minOf(first,current),maxOf(last,current),{vm.month(it);chooseMonth=false},{chooseMonth=false})
        }

    }
}

@Composable private fun WalletMonthPicker(selected:YearMonth,min:YearMonth,max:YearMonth,choose:(YearMonth)->Unit,dismiss:()->Unit) {
    var year by rememberSaveable { mutableStateOf(selected.year.toString()) }
    AnimatedGlassDialog(true,dismiss){GlassCard{Column(Modifier.padding(20.dp)){
        Text(wording("Choose month","选择月份"),style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(year,{year=it.filter(Char::isDigit).take(4)},label={Text(wording("Year","年份"))},singleLine=true)
        for(row in 0..3)Row {for(column in 1..3){val m=row*3+column;val target=year.toIntOrNull()?.takeIf{it in 1..9999}?.let{YearMonth.of(it,m)}
            TextButton({target?.let(choose)},Modifier.weight(1f),enabled=target!=null&&target>=min&&target<=max){Text(m.toString())}
        }}
        TextButton(dismiss){Text(wording("Cancel","取消"))}
    }}}
}
