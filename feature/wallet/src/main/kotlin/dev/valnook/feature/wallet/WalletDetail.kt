package dev.valnook.feature.wallet

import kotlin.math.roundToInt
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import java.time.*

@Composable internal fun WalletDetail(vm:WalletViewModel,card:WalletCard,cache:WalletImageCache,cashSlots:WalletCashSlots,
    onEntry:(Long,Long,Long)->Unit,onBalance:(Long,Long)->Unit,edit:()->Unit,
    onTitle:(String?)->Unit,onScrolled:(Boolean)->Unit,cardModifier:Modifier,frame:()->WalletSceneFrame,
    onReturn:()->Unit,returnEnabled:Boolean,returning:Boolean,progress:()->Float,privateState:WalletPrivateState,
    glow:()->Float,onGlowFade:(Float)->Unit) {
    key(card.id,card.boundCashAccountId) {
        val snapshot by vm.snapshot.collectAsStateWithLifecycle()
        val ledger by vm.ledger.collectAsStateWithLifecycle()
        val today by vm.today.collectAsStateWithLifecycle()
        val bounds by vm.bounds.collectAsStateWithLifecycle()
        val trend by vm.trend.collectAsStateWithLifecycle()
        val account=snapshot?.cash?.firstOrNull{it.id==card.boundCashAccountId}
        val parent=snapshot?.accounts?.firstOrNull{it.id==account?.account_id}
        val names=remember(snapshot){snapshot?.cash.orEmpty().associate { cash->cash.id to listOf(snapshot?.accounts?.firstOrNull{it.id==cash.account_id}?.name,cash.name).filterNotNull().joinToString(" · ") }}
        var collapse by rememberSaveable { mutableFloatStateOf(0f) }
        // The card glow fades out over the first tenth of the header collapse.
        fun glowFade()=(1f-collapse/.1f).coerceIn(0f,1f)
        DisposableEffect(privateState) {
            privateState.expand={
                if(collapse>0f)androidx.compose.animation.core.animate(collapse,0f,
                    animationSpec=androidx.compose.animation.core.tween(280,easing=WalletSelectionEasing)){value,_->collapse=value}
            }
            onDispose { privateState.expand={} }
        }
        val list=rememberLazyListState()
        var chooseMonth by rememberSaveable { mutableStateOf(false) }
        // Only what changes the page reaches composition; the collapse itself is read while laying out and drawing.
        val titleCollapsed by remember{derivedStateOf{collapse>.5f}}
        val cardTappable by remember{derivedStateOf{collapse<.8f}}
        val localTitle=if(titleCollapsed)account?.name?:card.name else card.name
        val scrolled by remember{derivedStateOf{collapse>0f||list.firstVisibleItemIndex>0||list.firstVisibleItemScrollOffset>0}}
        val days=remember(ledger.rows){ledger.rows.groupBy{Instant.ofEpochMilli(it.occurred_at_ms).atZone(vm.zone).toLocalDate()}}
        SideEffect{onTitle(localTitle)}
        // Told as they change, not on each recomposition, which a scroll no longer causes.
        val reportScrolled by rememberUpdatedState(onScrolled)
        val reportGlowFade by rememberUpdatedState(onGlowFade)
        LaunchedEffect(Unit){snapshotFlow{scrolled}.collect{reportScrolled(it)}}
        LaunchedEffect(Unit){snapshotFlow{glowFade()}.collect{reportGlowFade(it)}}
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
            val panelCorner=24.dp
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
                // At rest the card's glow spreads on every side. As the header collapses the glow fades out and the clip
                // closes in, but always reaches past the opaque panel's rounded corners: the panel covers the card, not the clip.
                Box(Modifier.fillMaxWidth().layout{measurable,constraints->
                    val height=constraints.constrainHeight((headerPx*(1f-collapse)).roundToInt().coerceAtLeast(0))
                    val placeable=measurable.measure(constraints.copy(minHeight=height,maxHeight=height))
                    layout(placeable.width,height){placeable.place(0,0)}
                }.drawWithContent {
                    clipRect(-size.width,-size.height,size.width*2,size.height+maxOf(panelCorner.toPx(),64.dp.toPx()*glowFade())){this@drawWithContent.drawContent()}
                },contentAlignment=Alignment.TopCenter) {
                    WalletFlippingCard(card,cache,privateState,cardModifier.width(width).requiredHeight(width/WalletRules.ASPECT).graphicsLayer {
                        translationY=-headerPx*collapse*.25f
                        // Stay opaque until the glow and shadow have faded: a translucent layer composites offscreen at the
                        // card's bounds and would cut them down to a rectangle. Still gone at 80% collapse, as before.
                        val visible=((.8f-collapse)/.7f).coerceIn(0f,1f)
                        alpha=if(returning)maxOf(visible,(progress()*4).coerceIn(0f,1f))else visible
                    }.semantics{contentDescription=card.name}.clickable(interactionSource=remember{androidx.compose.foundation.interaction.MutableInteractionSource()},
                        indication=null,enabled=returnEnabled&&cardTappable&&privateState.angle.value==0f&&!privateState.desiredBack,
                        onClickLabel=wording("Back to wallet","返回卡包"),onClick=onReturn),returnEnabled,{glow()*glowFade()},::glowFade)
                }
                GlassSurface(Modifier.fillMaxWidth().weight(1f,fill=false).padding(horizontal=16.dp).then(panelMotion),RoundedCornerShape(panelCorner),tintAlpha=1f) {
                    // Cards in the panel take a deeper colour than it, as on the page background.
                    CompositionLocalProvider(LocalGlassInset provides true) {
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
                                cashSlots.balanceSummary(account,parent?.name.orEmpty(),trend,{onBalance(account.account_id,account.id)},
                                    Modifier.padding(bottom=12.dp),snapshot?.cash.orEmpty(),names)
                            }
                            item("month") {
                                val current=YearMonth.from(today)
                                val first=bounds.firstMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
                                val last=bounds.lastMs?.let{YearMonth.from(Instant.ofEpochMilli(it).atZone(vm.zone))}?:current
                                val minimum=minOf(first,current);val maximum=maxOf(last,current)
                                val locale=LocalConfiguration.current.locales[0]
                                // As on the sub-account page: the title, with the month and its arrows at the end of its line.
                                Row(Modifier.fillMaxWidth().padding(top=12.dp).testTag("wallet-changes-header"),verticalAlignment=Alignment.CenterVertically) {
                                    Text(wording("Funds activity","资金变动"),Modifier.weight(1f).semantics{heading()},style=MaterialTheme.typography.titleMedium)
                                    IconButton({vm.month(maxOf(ledger.month.minusMonths(1),minimum))},Modifier.size(36.dp).testTag("wallet-month-previous"),
                                        enabled=ledger.month>minimum){ChevronIcon(false,wording("Previous month","上个月"))}
                                    TextButton({chooseMonth=true},Modifier.testTag("wallet-month"),contentPadding=PaddingValues(horizontal=8.dp)) {
                                        Text(localMonthLabel(ledger.month,locale),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
                                    }
                                    IconButton({vm.month(minOf(ledger.month.plusMonths(1),maximum))},Modifier.size(36.dp).testTag("wallet-month-next"),
                                        enabled=ledger.month<maximum){ChevronIcon(true,wording("Next month","下个月"))}
                                }
                            }
                            if(ledger.loading && ledger.rows.isEmpty())item("loading"){Box(Modifier.fillMaxWidth().height(80.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(22.dp))}}
                            else if(ledger.failed)item("error"){TextButton(vm::retry){Text(wording("Could not load this month. Retry","本月记录加载失败，点击重试"))}}
                            else if(ledger.rows.isEmpty())item("empty"){cashSlots.emptyMonth(Modifier.padding(top=8.dp))}
                            // Newest first, so each day's changes stay together in their order.
                            days.forEach { (date,rows) ->
                                item("day-$date"){cashSlots.dayGroup(date,rows){id->onEntry(account.account_id,account.id,id)}}
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
