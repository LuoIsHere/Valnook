@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package dev.valnook.feature.wallet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

data class WalletToolbar(val title:String,val detail:Boolean,val back:()->Unit,val add:()->Unit,val edit:()->Unit,val delete:()->Unit,val enabled:Boolean,val scrolled:Boolean,
    val flip:(()->Unit)?=null,val flipped:Boolean=false,val flipLoading:Boolean=false)
internal val WalletSelectionEasing=CubicBezierEasing(.28f,0f,.2f,1f)

@Composable fun WalletScreen(vm:WalletViewModel,onToolbar:(WalletToolbar?)->Unit,
    onEntry:(Long,Long,Long)->Unit,onBalance:(Long,Long)->Unit,
    overviewScroll:LazyListState=rememberLazyListState(),overviewBottomSpace:Dp=LocalPageBottomSpace.current,
    imageCache:WalletImageCache?=null) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    val visit by vm.visit.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.operationFailed.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val cache=imageCache?:remember(vm.sessionId){WalletImageCache(vm.repository)}
    val privateScope=rememberCoroutineScope()
    val privateState=remember(vm.sessionId,selected){WalletPrivateState(selected,vm.privateRepository,privateScope)}
    WalletPrivateProtection(privateState)
    var preparingReturn by remember(selected){mutableStateOf(false)}
    var returning by remember { mutableStateOf(false) }
    val scene=remember { Animatable(if(selected==null)0f else 1f) }
    val closingScene=remember { Animatable(0f) }
    var firstFrame by remember { mutableStateOf(true) }
    LaunchedEffect(selected,returning) {
        val restored=firstFrame&&selected!=null;firstFrame=false
        if(selected==null){scene.snapTo(0f);closingScene.snapTo(0f);returning=false}
        else if(returning){closingScene.animateTo(1f,tween(WalletReturnDuration,easing=LinearEasing));vm.select(null)}
        else if(restored)scene.snapTo(1f)
        else {scene.snapTo(0f);scene.animateTo(1f,tween(WalletEnterDuration,easing=LinearEasing))}
    }
    val returnToOverview:()->Unit={if(selected!=null&&!returning&&!preparingReturn&&!busy&&!privateState.editing&&!scene.isRunning){
        preparingReturn=true
        privateScope.launch { privateState.front();returning=true;preparingReturn=false }
    }}
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailScrolled by remember { mutableStateOf(false) }
    val overviewScrolled by remember(overviewScroll){derivedStateOf{overviewScroll.firstVisibleItemIndex>0||overviewScroll.firstVisibleItemScrollOffset>0}}
    var collapsedTitle by remember { mutableStateOf<String?>(null) }
    val title=wording("Wallet","卡包")
    val current=cards?.firstOrNull{it.id==selected}
    val toolbarTitle=if(selected!=null) collapsedTitle?:current?.name?:title else title
    val scrolled=if(selected==null||returning)overviewScrolled else detailScrolled
    val latestReturn by rememberUpdatedState(returnToOverview)
    DisposableEffect(vm,toolbarTitle,selected,current?.revision,busy,scrolled,returning,preparingReturn,scene.isRunning,
        privateState.editing,privateState.loading,privateState.desiredBack) {
        onToolbar(WalletToolbar(toolbarTitle,selected!=null,{latestReturn()},{vm.edit()},
            {privateScope.launch { privateState.front();vm.currentCard?.let{vm.edit(it)} }},{deleting=vm.selected.value},
            !busy&&!returning&&!preparingReturn&&!scene.isRunning&&!privateState.editing,scrolled,
            if(privateState.available)privateState::flip else null,privateState.desiredBack,privateState.loading))
        onDispose{onToolbar(null)}
    }
    BackHandler(selected!=null && draft==null&&!privateState.editing){returnToOverview()}
    LaunchedEffect(vm){while(true){vm.refreshTime();delay(30_000)}}
    if(cards==null) { if(failed) HintMessage(wording("Unable to load cards. Reopen Wallet to retry.","卡片加载失败，请重新打开卡包重试。"),isError=true) else PageLoading();return }
    val holder=rememberSaveableStateHolder()
    SharedTransitionLayout(Modifier.testTag("wallet-scene")) {
        val progress={if(returning)closingScene.value else scene.value}
        val frame={if(selected==null)WalletSceneFrame(0f,0f,1f)else walletSceneFrame(progress(),returning)}
        val selectedLayer=cards.orEmpty().lastIndex-cards.orEmpty().indexOfFirst{it.id==selected}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight=with(LocalDensity.current){maxHeight.toPx()}
            val edgeClearance=with(LocalDensity.current){24.dp.toPx()}
            val peek=with(LocalDensity.current){60.dp.toPx()}
            val detailTop=with(LocalDensity.current){LocalPageTopSpace.current.toPx()}
            val stackWidth=(maxWidth-48.dp).coerceAtMost(440.dp)
            val detailScale=stackWidth.coerceAtMost(maxHeight*.7f)/stackWidth
            val selectedTop=remember(selected,viewportHeight) {
                val info=overviewScroll.layoutInfo
                val item=info.visibleItemsInfo.firstOrNull{it.index==selectedLayer}
                (item?.offset?:((selectedLayer-overviewScroll.firstVisibleItemIndex)*peek-overviewScroll.firstVisibleItemScrollOffset).toInt())+info.beforeContentPadding
            }
            // Translate the entire foreground stack together, starting below the viewport.
            // Freeze this distance for the visit so animation never feeds back into layout.
            val frontTravel=remember(selected,viewportHeight) {
                val info=overviewScroll.layoutInfo
                val firstFront=info.visibleItemsInfo.firstOrNull{it.index==selectedLayer+1}
                (viewportHeight-(firstFront?.offset?:0)-info.beforeContentPadding+edgeClearance).coerceAtLeast(0f)
            }
            holder.SaveableStateProvider("overview") {
                CompositionLocalProvider(LocalPageBottomSpace provides overviewBottomSpace) {
                    Box(if(selected==null)Modifier else Modifier.clearAndSetSemantics{}) {
                    WalletStack(cards.orEmpty(),busy||selected!=null,cache,vm::select,vm::reorder,overviewScroll,
                        {vm.edit()},cardModifier={card->
                            val shared=rememberSharedContentState("${vm.sessionId}:${card.id}")
                            val layer=cards.orEmpty().lastIndex-cards.orEmpty().indexOfFirst{it.id==card.id}
                            val visibleFace=remember(layer,cards?.size){WalletStackVisibleShape(layer<cards.orEmpty().lastIndex)}
                            val front=layer>selectedLayer
                            // Both matched and unmatched cards participate in one ordered overlay.
                            // The front cards occlude the moving card before it reaches its slot.
                            Modifier.renderInSharedTransitionScopeOverlay(layer.toFloat(),renderInOverlay={selected!=null&&!shared.isMatchFound})
                                .sharedElementWithCallerManagedVisibility(shared,visible=card.id!=selected||returning,
                                    zIndexInOverlay=layer.toFloat(),boundsTransform={_,_->
                                        if(returning)tween(WalletReturnCardDuration,delayMillis=WalletReturnCardDelay,easing=WalletSelectionEasing)else tween(WalletEnterDuration,easing=WalletSelectionEasing)})
                                .graphicsLayer{
                                    val motion=frame()
                                    val back=selected!=null&&layer<selectedLayer
                                    val fold=if(back)motion.backFold else 0f
                                    val scale=1f+(detailScale-1f)*fold
                                    val originalTop=selectedTop-(selectedLayer-layer)*peek
                                    alpha=1f
                                    transformOrigin=TransformOrigin(0f,0f)
                                    scaleX=scale;scaleY=scale
                                    translationX=size.width*(1f-scale)/2f
                                    translationY=when {
                                        front->motion.frontOffset*frontTravel
                                        back->(detailTop-originalTop)*fold
                                        else->0f
                                    }
                                    // Shrink each exposed lip together with the physical gap.
                                    // Once stacked, even a collapsed detail cannot expose back cards.
                                    shape=if(back)WalletStackVisibleShape(true,(1f-fold)/scale)else visibleFace
                                    clip=selected!=null&&card.id!=selected
                                }
                        })
                    }
                }
            }
            current?.let { card ->
                holder.SaveableStateProvider("${card.id}:$visit") {
                    WalletDetail(vm,card,cache,onEntry,onBalance,{vm.edit(card)},
                        {collapsedTitle=it},{detailScrolled=it},Modifier.sharedElementWithCallerManagedVisibility(
                            rememberSharedContentState("${vm.sessionId}:${card.id}"),visible=!returning,
                            zIndexInOverlay=(cards.orEmpty().lastIndex-cards.orEmpty().indexOfFirst{it.id==card.id}).toFloat(),
                            boundsTransform={_,_->if(returning)tween(WalletReturnCardDuration,delayMillis=WalletReturnCardDelay,easing=WalletSelectionEasing)else tween(WalletEnterDuration,easing=WalletSelectionEasing)}),
                        frame,returnToOverview,!busy&&!returning&&!preparingReturn&&!scene.isRunning,returning,progress,privateState)
                }
            }
            AnimatedVisibility(privateState.copyNotice != null,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=24.dp).zIndex(100f),
                enter=fadeIn(tween(150))+slideInVertically(tween(180)){it/3},exit=fadeOut(tween(150))) {
                // Generic feedback only: never interpolate the copied field or its value.
                // 提示只包含通用状态，禁止拼接字段名称、卡号、有效期或 CVV。
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=.96f),
                    contentColor=MaterialTheme.colorScheme.onSurfaceVariant,shadowElevation=2.dp,
                    modifier=Modifier.testTag("wallet-copy-notice").semantics(mergeDescendants=true) { liveRegion=LiveRegionMode.Polite }) {
                    Text(if(privateState.lastCopySucceeded)wording("Copied","复制成功")else wording("Copy failed. Try again.","复制失败，请重试"),
                        Modifier.padding(horizontal=22.dp,vertical=12.dp),style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if(error && draft==null) AnimatedGlassDialog(true,{vm.operationFailed.value=false}) {
        GlassCard { Column(Modifier.padding(24.dp)) {
            Text(wording("Changes could not be saved. Your data is unchanged; please retry.","保存失败，已有数据未改变，请重试。"))
            TextButton({vm.operationFailed.value=false}){Text(wording("OK","知道了"))}
        } }
    }
    cards?.firstOrNull{it.id==deleting}?.let { card ->
        AnimatedGlassDialog(true,{if(!busy)deleting=null}) {GlassCard{Column(Modifier.padding(24.dp)){
            Text(wording("Delete this card?","删除这张卡片？"),style=MaterialTheme.typography.titleLarge)
            Text(wording("The card and any private back details are deleted. The linked account and its records are kept.","只删除卡片及其本机私密背面内容，不删除绑定账户及其记录。"),Modifier.padding(vertical=16.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton({deleting=null},enabled=!busy){Text(wording("Cancel","取消"))}
                TextButton({deleting=null;vm.delete(card)},enabled=!busy,modifier=Modifier.testTag("wallet-delete-confirm")){Text(wording("Delete","删除"),color=MaterialTheme.colorScheme.error)}
            }
        }}}
    }
    draft?.let { WalletEditor(vm,cache,it) }
    WalletPrivateDialogs(privateState)
}

@Composable private fun WalletStack(cards:List<WalletCard>,busy:Boolean,cache:WalletImageCache,
    select:(Long)->Unit,reorder:(List<Long>,List<Long>)->Unit,list:LazyListState,add:()->Unit,
    cardModifier:@Composable (WalletCard)->Modifier) {
    if(cards.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(pageContentPadding()).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Text(wording("A place for your cards","为喜欢的卡片留个位置"),style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp));Text(wording("Collect a card face, or link an existing account.","收藏卡面，或关联已有子账户。"),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp));Button(add,Modifier.testTag("wallet-add-empty")){Text(wording("Add card","添加卡片"))}
        };return
    }
    val haptics=androidx.compose.ui.platform.LocalHapticFeedback.current
    val original=cards.map{it.id}
    var order by remember { mutableStateOf(original.reversed()) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    var startScroll by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Long?>(null) }
    val releasePosition=remember { Animatable(0f) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(original,busy,dragging,settling){if(!busy && dragging==null&&settling==null)order=original.reversed()}
    val lookup=cards.associateBy{it.id}
    val upLabel=wording("Move up","上移");val downLabel=wording("Move down","下移")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width=(maxWidth-48.dp).coerceAtMost(440.dp)
        val height=width/WalletRules.ASPECT
        val density=LocalDensity.current
        val peek=with(density){60.dp.toPx()}
        val opening=with(density){8.dp.toPx()}
        fun scrollPosition()=list.firstVisibleItemIndex*peek+list.firstVisibleItemScrollOffset
        fun dragPosition()=startY+dragY+scrollPosition()-startScroll
        fun replaceOrder(next:List<Long>) {
            // A keyed LazyColumn otherwise follows the old first item when its index
            // changes, moving the viewport under a stationary finger.
            list.requestScrollToItem(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset)
            order=next
        }
        fun relocate() {
            val id=dragging?:return
            val from=order.indexOf(id)
            val to=walletDropIndex(dragPosition(),from,order.size,peek,opening)
            if(from>=0&&to>=0&&from!=to) {
                replaceOrder(order.toMutableList().apply{add(to,removeAt(from))})
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick)
            }
        }
        fun finishDrag(cancelled:Boolean) {
            val id=dragging?:return
            val position=dragPosition()
            scope.launch(start=CoroutineStart.UNDISPATCHED) {
                releasePosition.snapTo(position)
                settling=id
                dragging=null
                if(cancelled)replaceOrder(original.reversed())
                else if(order.reversed()!=original)reorder(original,order.reversed())
            }
        }
        BackHandler(dragging!=null){finishDrag(true)}
        LaunchedEffect(settling,order) {
            val id=settling?:return@LaunchedEffect
            val index=order.indexOf(id)
            if(index>=0)releasePosition.animateTo(index*peek,spring(dampingRatio=.92f,stiffness=480f))
            settling=null
        }
        LaunchedEffect(dragging) {
            while(dragging!=null) {
                val info=list.layoutInfo
                // Use the exposed card lip, not the hidden full card's lower edge.
                val position=dragPosition()-scrollPosition()
                val delta=when {position<info.viewportStartOffset+peek -> -12f
                    position+peek>info.viewportEndOffset-peek -> 12f;else->0f}
                if(delta!=0f){list.scrollBy(delta);relocate()}
                delay(16)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("wallet-stack"),state=list,contentPadding=pageContentPadding(),
            userScrollEnabled=!busy&&dragging==null&&settling==null,
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(60.dp-height)) {
            itemsIndexed(order,key={_,id->id}) { index,id ->
                lookup[id]?.let { card ->
                    val dragIndex=order.indexOf(dragging)
                    val gap=when {dragIndex<0||id==dragging->0f;index<dragIndex->-opening;else->opening}
                    val slot by animateFloatAsState(index*peek+gap,
                        spring(dampingRatio=.92f,stiffness=480f),label="wallet-slot")
                    val priority=original.indexOf(id)
                    Box(Modifier.width(width).zIndex(index.toFloat())
                        .graphicsLayer {
                            val position=when(id){dragging->dragPosition();settling->releasePosition.value;else->slot}
                            translationY=position-index*peek
                        }
                        .pointerInput(id,busy,peek) {
                            if(!busy)detectDragGesturesAfterLongPress(onDragStart=start@{
                                if(settling!=null)return@start
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                startY=order.indexOf(id)*peek;startScroll=scrollPosition();dragY=0f;dragging=id
                            },onDragCancel={finishDrag(true)},onDragEnd={finishDrag(false)}){change,delta->
                                if(dragging==id){change.consume();dragY+=delta.y;relocate()}
                            }
                        }
                        .semantics { contentDescription=card.name; customActions=listOf(
                            CustomAccessibilityAction(upLabel){if(priority>0&&!busy){reorder(original,original.toMutableList().apply{add(priority-1,removeAt(priority))});true}else false},
                            CustomAccessibilityAction(downLabel){if(priority<original.lastIndex&&!busy){reorder(original,original.toMutableList().apply{add(priority+1,removeAt(priority))});true}else false}) }
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(interactionSource=remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication=null,enabled=!busy&&dragging==null&&settling==null){select(id)}) {
                        WalletCardView(card,cache,cardModifier(card))
                    }
                }
            }
        }
    }
}
