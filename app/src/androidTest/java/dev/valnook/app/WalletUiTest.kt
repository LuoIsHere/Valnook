package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.data.image.WalletImages
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.repository.*
import dev.valnook.feature.wallet.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.time.YearMonth

@HiltAndroidTest
class WalletUiTest {
    @get:Rule(order=0)val hilt=HiltAndroidRule(this)
    @get:Rule(order=1)val rule=createAndroidComposeRule<MainActivity>()
    @Before fun prepare(){hilt.inject()}
    private val graph get()=rule.activity.sessions.session.value.graph
    private val wallet get()=requireNotNull(graph.wallet)
    private fun switchDemo(enabled:Boolean) {
        // Match the UI's main-thread lifecycle scope; never replace the composition
        // while a session change is still delivering its initial settings snapshot.
        val change=rule.runOnUiThread { rule.activity.lifecycleScope.async {
            if(enabled)rule.activity.sessions.enterDemo() else rule.activity.sessions.exitDemo()
        } }
        rule.waitUntil(20000){change.isCompleted}
        runBlocking{change.await()}
        rule.waitForIdle()
    }
    private fun save(name:String) {
        rule.waitForIdle()
        // Capture all windows: root ordering is not stable while glass dialogs animate.
        val bitmap=if(name.startsWith("wallet-selection-")||name.startsWith("wallet-return-"))
            rule.onNodeWithTag("wallet-scene").captureToImage().asAndroidBitmap()
        else requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun root(feedback:androidx.compose.ui.hapticfeedback.HapticFeedback?=null){
        rule.runOnUiThread{rule.activity.setContent{
            if(feedback==null)ValnookRoot(rule.activity.sessions,rule.activity.webAdmin)
            else CompositionLocalProvider(androidx.compose.ui.platform.LocalHapticFeedback provides feedback){ValnookRoot(rule.activity.sessions,rule.activity.webAdmin)}
        }};rule.waitForIdle()
        rule.waitUntil(15000){rule.onAllNodesWithTag("nav-wallet").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun card(name:String,bound:Long?=null,color:Int=0xff566775.toInt()):Long=runBlocking{
        val bitmap=Bitmap.createBitmap(1000,631,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        val image=WalletImages.encode(bitmap,1f,0f,0f);bitmap.recycle()
        wallet.save(null,null,name,bound,image.key,image)
    }
    @Test fun empty_create_edit_and_return_use_one_toolbar_and_hide_capsule_in_detail() {
        root();rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-add-empty").fetchSemanticsNodes().isNotEmpty()}
        save("wallet-empty")
        rule.onNodeWithTag("wallet-add-empty").performClick()
        rule.onNodeWithTag("wallet-name").performTextInput("Collection")
        rule.onNodeWithTag("wallet-save").performScrollTo().performClick()
        rule.waitUntil(10000){runBlocking{wallet.observeCards().first()}.size==1}
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty()}
        rule.onAllNodesWithTag("root-toolbar").assertCountEquals(1)
        rule.onNodeWithTag("root-capsule").assertDoesNotExist()
        rule.onNodeWithTag("wallet-switch").assertDoesNotExist()
        rule.onNodeWithTag("wallet-collapse").assertDoesNotExist()
        save("wallet-unbound")
        val toolbar=rule.onNodeWithTag("root-toolbar").fetchSemanticsNode().boundsInRoot
        val edit=rule.onNodeWithTag("wallet-edit").fetchSemanticsNode().boundsInRoot
        val delete=rule.onNodeWithTag("wallet-delete").fetchSemanticsNode().boundsInRoot
        assertTrue(toolbar.contains(edit.center));assertTrue(toolbar.contains(delete.center));assertTrue(delete.center.x>edit.center.x)
        rule.onNodeWithTag("wallet-edit").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-name").fetchSemanticsNodes().size==1};rule.waitForIdle()
        rule.onNodeWithTag("wallet-name").performClick().assertIsFocused()
        rule.waitForIdle()
        rule.onNodeWithTag("wallet-name").performTextReplacement("Renamed")
        rule.onNodeWithTag("wallet-save").performScrollTo().performClick()
        rule.waitUntil(10000){runBlocking{wallet.observeCards().first()}.single().name=="Renamed"}
        rule.onNodeWithContentDescription(rule.activity.getString(dev.valnook.app.R.string.nav_back)).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-stack").assertExists()
        rule.onNodeWithTag("root-capsule").assertExists()
        save("wallet-single")
    }
    @Test fun stack_selection_month_switch_and_return_preserve_reading_state() {
        val cash=runBlocking{
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Wallet bank","",listOf(CashBalanceChange("USD",123450,null,name="Everyday account"))))
            graph.overview.snapshot().cash.single()
        }
        val first=card("Mist",cash.id);card("Sandstone",color=0xff877e73.toInt());card("Dusk",color=0xff363a42.toInt())
        val vm=rule.runOnUiThread{WalletViewModel(graph.sessionId,wallet,graph.overview,graph.cashPages,graph.clock,SavedStateHandle())}
        rule.runOnUiThread{rule.activity.setContent{ValnookTheme(dark_theme=true){WalletScreen(vm,{}, {_,_,_->},{_,_->})}}}
        rule.waitUntil(10000){vm.cards.value?.size==3};rule.waitForIdle();save("wallet-stack-dark")
        rule.onNodeWithTag("wallet-card-$first",useUnmergedTree=true).performClick()
        rule.waitUntil(10000){!vm.ledger.value.loading&&vm.selected.value==first}
        assertEquals(YearMonth.of(2026,10),vm.ledger.value.month)
        rule.onNodeWithTag("wallet-month").performScrollTo();save("wallet-detail-dark")
        rule.runOnUiThread{vm.month(YearMonth.of(2026,9))}
        rule.waitUntil(10000){!vm.ledger.value.loading};assertTrue(vm.ledger.value.rows.isEmpty());save("wallet-empty-month")
        rule.runOnUiThread{vm.month(YearMonth.of(2026,10))}
        rule.waitUntil(10000){!vm.ledger.value.loading};assertEquals(1,vm.ledger.value.rows.size)
        rule.runOnUiThread{vm.select(null)};rule.waitForIdle();rule.onNodeWithTag("wallet-stack").assertExists()
        rule.runOnUiThread{rule.activity.setContent{ValnookTheme(dark_theme=false){WalletScreen(vm,{}, {_,_,_->},{_,_->})}}}
        rule.waitForIdle();save("wallet-stack-light")
    }
    @Test fun fifty_cards_scroll_and_reorder_persists_without_a_sorting_menu() {
        val ids=(1..50).map{card("Card $it",color=0xff000000.toInt() or ((70+it)*0x010101))}
        root();rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(0)
        save("wallet-fifty-back")
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(49)
        save("wallet-fifty-front")
        rule.onNodeWithTag("wallet-manage").assertDoesNotExist()
        runBlocking{wallet.reorder(ids,ids.reversed())}
        assertEquals(ids.reversed(),runBlocking{wallet.observeCards().first()}.map{it.id})
        rule.waitForIdle();save("wallet-fifty-reordered")
    }
    @Test fun long_press_drag_saves_once_and_returns_to_overview() {
        val ids=(1..5).map{card("Card $it",color=0xff000000.toInt() or ((60+it*22)*0x010101))}
        val middle=ids[2]
        val signals=mutableListOf<androidx.compose.ui.hapticfeedback.HapticFeedbackType>()
        val feedback=object:androidx.compose.ui.hapticfeedback.HapticFeedback {override fun performHapticFeedback(hapticFeedbackType:androidx.compose.ui.hapticfeedback.HapticFeedbackType){signals+=hapticFeedbackType}}
        root(feedback);rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        val original=runBlocking{wallet.observeCards().first()}.map{it.id}
        fun top(id:Long)=rule.onNodeWithTag("wallet-card-$id",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
        val backSpacing=top(ids[3])-top(ids[4]);val frontSpacing=top(ids[0])-top(ids[1])
        rule.onNodeWithTag("wallet-card-$middle",useUnmergedTree=true).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(center.x,20f));advanceEventTime(700);moveBy(androidx.compose.ui.geometry.Offset(0f,1f))
        }
        rule.waitForIdle();save("wallet-drag-held")
        assertEquals(backSpacing,top(ids[3])-top(ids[4]),2f)
        assertEquals(frontSpacing,top(ids[0])-top(ids[1]),2f)
        assertEquals(original,runBlocking{wallet.observeCards().first()}.map{it.id})
        assertEquals(1,signals.size)
        rule.mainClock.autoAdvance=false
        try {
            repeat(8) {
                val previousTop=top(middle)
                rule.onNodeWithTag("wallet-card-$middle",useUnmergedTree=true).performTouchInput {
                    moveBy(androidx.compose.ui.geometry.Offset(0f,-25f),16)
                }
                rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
                assertEquals("Dragged card must follow the finger across a slot change",previousTop-25f,top(middle),2f)
            }
        }finally{rule.mainClock.autoAdvance=true}
        rule.waitForIdle();save("wallet-drag-moving")
        val crossings=signals.size
        rule.mainClock.advanceTimeBy(160);rule.waitForIdle()
        assertEquals(crossings,signals.size)
        rule.onNodeWithTag("wallet-card-$middle",useUnmergedTree=true).performTouchInput {up()}
        rule.waitUntil(10000){runBlocking{wallet.observeCards().first()}.map{it.id}!=original}
        assertEquals(1,signals.count{it==androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress})
        assertTrue(signals.any{it==androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentFrequentTick})
        rule.onNodeWithTag("wallet-sort-done").assertDoesNotExist()
        rule.waitForIdle();save("wallet-sorting")
        assertEquals(original.toSet(),runBlocking{wallet.observeCards().first()}.map{it.id}.toSet())
        rule.onNodeWithTag("wallet-card-$middle",useUnmergedTree=true).performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,20f))}
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun photo_crop_rotate_save_survives_source_removal() {
        val file=java.io.File(rule.activity.cacheDir,"wallet-fixture-${UUID.randomUUID()}.png")
        val source=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888);source.eraseColor(0xff69818b.toInt())
        file.outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
        val registry=object:androidx.activity.result.ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:androidx.activity.result.contract.ActivityResultContract<I,O>,input:I,options:androidx.core.app.ActivityOptionsCompat?) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    dispatchResult(requestCode,android.app.Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.fromFile(file)))
                }
            }
        }
        val owner=object:androidx.activity.result.ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        val vm=rule.runOnUiThread{WalletViewModel(graph.sessionId,wallet,graph.overview,graph.cashPages,graph.clock,SavedStateHandle())}
        try {
            rule.runOnUiThread{rule.activity.setContent{CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner){ValnookTheme(dark_theme=true){WalletScreen(vm,{}, {_,_,_->},{_,_->})}}}}
            rule.waitUntil(10000){vm.cards.value!=null};rule.runOnUiThread{vm.edit()}
            rule.onNodeWithTag("wallet-name").performTextInput("Imported face")
            rule.onNodeWithTag("wallet-photo").performScrollTo().performClick()
            try { rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-crop").fetchSemanticsNodes().isNotEmpty()} }
            catch(error:AssertionError){save("wallet-crop-failure");throw error}
            rule.onNodeWithTag("wallet-rotate").performClick();rule.waitForIdle();save("wallet-crop-dark")
            rule.onNodeWithTag("wallet-crop-confirm").performClick()
            rule.waitUntil(10000){vm.draft.value?.image!=null}
            rule.onNodeWithTag("wallet-save").performScrollTo().performClick()
            rule.waitUntil(10000){vm.draft.value==null&&vm.cards.value?.size==1}
            assertTrue(file.delete())
            val card=runBlocking{wallet.observeCards().first()}.single()
            assertNotNull(runBlocking{wallet.image(requireNotNull(card.imageKey))})
            save("wallet-imported-face")
        }finally{file.delete()}
    }
    @Test fun bundled_demo_has_cards_and_rejects_expired_session_writes() {
        val original=wallet;card("Real collection")
        root();switchDemo(true)
        val demo=wallet;val cards=runBlocking{demo.observeCards().first()}
        assertEquals(15,cards.size);assertTrue(cards.any{it.boundCashAccountId==null})
        assertTrue(cards.all{runBlocking{demo.image(requireNotNull(it.imageKey))}!=null})
        assertTrue(runBlocking{runCatching{original.save(null,null,"Stale",null,null)}}.isFailure)
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        save("wallet-demo-overview")
        rule.onNodeWithTag("wallet-card-${cards.last().id}",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(cards.lastIndex)
        rule.onNodeWithTag("wallet-card-${cards.first().id}",useUnmergedTree=true).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty()}
        save("wallet-demo-detail")
        switchDemo(false)
        assertEquals("Real collection",runBlocking{wallet.observeCards().first()}.single().name)
        assertTrue(runBlocking{runCatching{demo.save(null,null,"Expired",null,null)}}.isFailure)
    }
    @Test fun overview_starts_at_top_and_restores_scroll_between_tabs_and_details() {
        val ids=(1..15).map{card("Position $it",color=when(it){8->0xff2754b8.toInt();7->0xffb0ac7a.toInt();else->0xff406658.toInt()})}
        root();rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-card-${ids.last()}",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithTag("root-toolbar-background").assertDoesNotExist()
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(7)
        rule.waitForIdle();rule.onNodeWithTag("root-toolbar-background").assertExists()
        val visible=ids.reversed()[7]
        val before=rule.onNodeWithTag("wallet-card-$visible",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("nav-accounts").performClick()
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitForIdle()
        assertEquals(before,rule.onNodeWithTag("wallet-card-$visible",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,2f)
        val frontBounds=rule.onNodeWithTag("wallet-card-${ids[6]}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        fun frontPixel(bounds:androidx.compose.ui.geometry.Rect=frontBounds):Int {
            val bitmap=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            return bitmap.getPixel(bounds.center.x.toInt(),(bounds.top+30f).toInt()).also{bitmap.recycle()}
        }
        val coveredPixel=frontPixel()
        rule.mainClock.autoAdvance=false
        try {
            rule.onNodeWithTag("wallet-card-$visible",useUnmergedTree=true).performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,20f))}
            rule.mainClock.advanceTimeBy(16);save("wallet-selection-016ms")
            rule.mainClock.advanceTimeBy(80);save("wallet-selection-096ms")
            rule.mainClock.advanceTimeBy(144);save("wallet-selection-240ms")
            rule.mainClock.advanceTimeBy(320);save("wallet-selection-560ms")
        }finally{rule.mainClock.autoAdvance=true}
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle();rule.onNodeWithTag("root-toolbar-background").assertDoesNotExist()
        rule.mainClock.autoAdvance=false
        try {
            rule.onNode(hasTestTag("wallet-card-$visible") and hasAnyAncestor(hasTestTag("wallet-detail")),useUnmergedTree=true).performClick()
            rule.mainClock.advanceTimeBy(16);save("wallet-return-016ms")
            rule.mainClock.advanceTimeBy(144);save("wallet-return-160ms")
            rule.mainClock.advanceTimeBy(160);save("wallet-return-320ms")
            rule.mainClock.advanceTimeBy(80);save("wallet-return-400ms")
            val rising=rule.onNodeWithTag("wallet-card-${ids[6]}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("Foreground must still be rising from below",rising.top>frontBounds.top+20f)
            assertEquals("Moving foreground must retain its fully opaque face",coveredPixel,frontPixel(rising))
            rule.mainClock.advanceTimeBy(300);save("wallet-return-700ms")
            assertEquals(coveredPixel,frontPixel())
        }finally{rule.mainClock.autoAdvance=true}
        rule.waitForIdle()
        assertEquals(before,rule.onNodeWithTag("wallet-card-$visible",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,2f)
        save("wallet-scroll-restored")
    }
    @Test fun upper_cards_fold_behind_the_selected_card_and_unfold_on_return() {
        val colors=listOf(0xffb0ac7a.toInt(),0xff2754b8.toInt(),0xff406658.toInt(),0xff86543a.toInt(),0xff635680.toInt())
        val ids=colors.mapIndexed{index,color->card("Fold $index",color=color)}
        root();rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
        val selected=ids[1];val back=ids[2]
        val selectedBounds=rule.onNodeWithTag("wallet-card-$selected",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val backBounds=rule.onNodeWithTag("wallet-card-$back",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val baseline=rule.onNodeWithTag("wallet-scene").captureToImage().asAndroidBitmap()
        val x=selectedBounds.center.x.toInt()
        val selectedColor=baseline.getPixel(x,(selectedBounds.top+30f).toInt())
        val backColor=baseline.getPixel(x,(backBounds.top+30f).toInt())
        baseline.recycle()
        val originalGap=selectedBounds.top-backBounds.top
        fun visibleGap():Float {
            val bitmap=rule.onNodeWithTag("wallet-scene").captureToImage().asAndroidBitmap()
            try {
                fun first(color:Int)=(0 until bitmap.height).firstOrNull{bitmap.getPixel(x,it)==color}
                    ?: throw AssertionError("Opaque card face disappeared instead of being occluded")
                return (first(selectedColor)-first(backColor)).toFloat()
            }finally{bitmap.recycle()}
        }
        rule.mainClock.autoAdvance=false
        try {
            rule.onNodeWithTag("wallet-card-$selected",useUnmergedTree=true).performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,20f))}
            rule.mainClock.advanceTimeBy(240);save("wallet-selection-fold-240ms")
            val folded=visibleGap()
            assertTrue("Upper card lips must converge behind selection",folded>0f&&folded<originalGap*.85f)
            rule.mainClock.advanceTimeBy(440)
        }finally{rule.mainClock.autoAdvance=true}
        rule.waitForIdle()
        val detail=rule.onNodeWithTag("wallet-scene").captureToImage().asAndroidBitmap()
        try {assertFalse("No upper card face may remain exposed in detail",(0 until detail.height).any{detail.getPixel(x,it)==backColor})}
        finally{detail.recycle()}
        rule.mainClock.autoAdvance=false
        try {
            rule.onNode(hasTestTag("wallet-card-$selected") and hasAnyAncestor(hasTestTag("wallet-detail")),useUnmergedTree=true).performClick()
            rule.mainClock.advanceTimeBy(240);save("wallet-return-unfold-240ms")
            val early=visibleGap()
            rule.mainClock.advanceTimeBy(160);save("wallet-return-unfold-400ms")
            val later=visibleGap()
            assertTrue("Upper cards must spread with the returning selection",early>0f&&later>early&&later<originalGap)
            rule.mainClock.advanceTimeBy(300)
        }finally{rule.mainClock.autoAdvance=true}
        rule.waitForIdle()
        assertEquals(originalGap,visibleGap(),2f)
    }
    @Test fun startup_preloads_card_faces_and_detail_retains_the_same_stack() {
        val ids=(1..15).map{card("Warm $it",color=0xff000000.toInt() or ((50+it*8)*0x010101))}
        root()
        val overview=rule.runOnUiThread {
            androidx.lifecycle.ViewModelProvider(rule.activity)["wallet-overview-${graph.sessionId}",WalletOverviewViewModel::class.java]
        }
        val last=runBlocking{wallet.observeCards().first()}.last()
        rule.waitUntil(10000){overview.cards.value?.size==15&&overview.cache.peek(requireNotNull(last.imageKey))!=null}
        // This is checked before the user ever opens Wallet.
        rule.onNodeWithTag("wallet-stack").assertDoesNotExist()
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(4)
        val position=rule.runOnUiThread{overview.scroll.firstVisibleItemIndex to overview.scroll.firstVisibleItemScrollOffset}
        val visible=ids.reversed()[4]
        val selectedImage=requireNotNull(overview.cards.value?.first{it.id==visible}?.imageKey)
        rule.waitUntil(10000){overview.cache.peek(selectedImage)!=null}
        val warmed=overview.cache.peek(selectedImage)
        rule.onNodeWithTag("wallet-card-$visible",useUnmergedTree=true).performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,20f))}
        rule.waitForIdle()
        // The hidden stack stays laid out but has no accessible duplicate controls.
        rule.onNodeWithTag("wallet-stack").assertDoesNotExist()
        assertTrue(rule.runOnUiThread{overview.scroll.layoutInfo.visibleItemsInfo.isNotEmpty()})
        val inDetail=overview.cache.peek(selectedImage)
        assertSame("Visible card decode must survive entry (${warmed?.bitmap?.width} -> ${inDetail?.bitmap?.width})",warmed,inDetail)
        rule.onNode(hasTestTag("wallet-card-$visible") and hasAnyAncestor(hasTestTag("wallet-detail")),useUnmergedTree=true).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("wallet-detail").assertDoesNotExist()
        assertEquals(position,rule.runOnUiThread{overview.scroll.firstVisibleItemIndex to overview.scroll.firstVisibleItemScrollOffset})
        save("wallet-warm-return")
    }
    @Test fun concurrent_card_requests_share_one_decode_and_memory_trim_can_reload() {
        card("Shared image")
        val imageKey=requireNotNull(runBlocking{wallet.observeCards().first()}.single().imageKey)
        val reads=java.util.concurrent.atomic.AtomicInteger()
        val counting=object:WalletRepository by wallet {
            override suspend fun image(key:String):dev.valnook.domain.model.WalletImage? {
                reads.incrementAndGet();kotlinx.coroutines.delay(20);return wallet.image(key)
            }
        }
        val cache=WalletImageCache(counting)
        val images=runBlocking{(1..20).map{async{cache.load(imageKey,600)}}.map{it.await()}}
        assertEquals(1,reads.get());assertNotNull(images.first())
        assertTrue(images.all{it===images.first()})
        assertEquals(600,requireNotNull(images.first()).bitmap.width)
        cache.clear();assertNull(cache.peek(imageKey))
        assertNotNull(runBlocking{cache.load(imageKey,600)});assertEquals(2,reads.get())
    }
    @Test fun system_back_from_collapsed_detail_restores_the_card_stack() {
        root();switchDemo(true)
        rule.waitUntil(10000){rule.onAllNodesWithTag("nav-wallet").fetchSemanticsNodes().isNotEmpty()}
        val cards=runBlocking{wallet.observeCards().first()}
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-stack").performScrollToIndex(cards.lastIndex-1)
        val selected=cards[1].id
        val before=rule.onNodeWithTag("wallet-card-$selected",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("wallet-card-$selected",useUnmergedTree=true).performTouchInput{click(androidx.compose.ui.geometry.Offset(center.x,20f))}
        rule.waitForIdle()
        rule.onNodeWithTag("wallet-ledger").performTouchInput{swipeUp()}
        rule.waitForIdle();rule.onNodeWithTag("root-toolbar-background").assertExists()
        rule.mainClock.autoAdvance=false
        try {
            rule.runOnUiThread{rule.activity.onBackPressedDispatcher.onBackPressed()}
            rule.mainClock.advanceTimeBy(160);save("wallet-return-collapsed-160ms")
            rule.mainClock.advanceTimeBy(240);save("wallet-return-collapsed-400ms")
            rule.mainClock.advanceTimeBy(200)
        } finally {rule.mainClock.autoAdvance=true}
        rule.waitForIdle();rule.onNodeWithTag("wallet-detail").assertDoesNotExist()
        assertEquals(before,rule.onNodeWithTag("wallet-card-$selected",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,2f)
    }
    @Test fun credit_detail_handles_large_type_narrow_and_landscape_layouts() {
        switchDemo(true)
        val cards=runBlocking{wallet.observeCards().first()}
        val vm=rule.runOnUiThread{WalletViewModel(graph.sessionId,wallet,graph.overview,graph.cashPages,graph.clock,SavedStateHandle())}
        rule.waitUntil(10000){vm.cards.value!=null}
        rule.runOnUiThread{vm.select(cards[1].id)}
        rule.waitUntil(10000){vm.snapshot.value!=null&&!vm.ledger.value.loading}
        rule.runOnUiThread{rule.activity.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
                ValnookTheme(dark_theme=true){Box(Modifier.fillMaxSize().wrapContentSize(androidx.compose.ui.Alignment.TopStart).width(androidx.compose.ui.unit.Dp(320f)).fillMaxHeight()) {WalletScreen(vm,{}, {_,_,_->},{_,_->})}}
            }
        }}
        rule.waitForIdle();rule.onNodeWithTag("wallet-ledger").performScrollToIndex(0);save("wallet-credit-large-narrow")
        rule.onNodeWithTag("wallet-ledger").performTouchInput{swipeUp()};save("wallet-credit-collapsed")
        rule.runOnUiThread{rule.activity.setContent{
            ValnookTheme(dark_theme=false){Box(Modifier.fillMaxSize().wrapContentSize(androidx.compose.ui.Alignment.TopStart).fillMaxWidth().height(androidx.compose.ui.unit.Dp(320f))) {WalletScreen(vm,{}, {_,_,_->},{_,_->})}}
        }}
        rule.waitForIdle();save("wallet-landscape-height")
    }
    @Test fun existing_record_route_returns_to_selected_wallet_month() {
        val cash=runBlocking {
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"History bank","",listOf(CashBalanceChange("USD",30000,null,name="Everyday"))))
            val account=graph.overview.snapshot().cash.single()
            val entry=graph.cash.observeCashEntries(account.id,50).first().single()
            graph.commands.execute(EditCashEntry(UUID.randomUUID().toString(),entry.id,entry.revision,entry.delta_minor,
                java.time.LocalDate.of(2026,9,22).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),"September"))
            account
        }
        val id=card("History",cash.id)
        root();rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-card-$id",useUnmergedTree=true).performClick()
        rule.onNodeWithTag("wallet-month-previous").performScrollTo().performClick()
        val manual=rule.activity.getString(dev.valnook.core.designsystem.R.string.manual_balance_change)
        rule.waitUntil(10000){rule.onAllNodesWithText(manual).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText(manual).performScrollTo().performClick()
        rule.waitUntil(10000){rule.onAllNodesWithText(rule.activity.getString(dev.valnook.core.designsystem.R.string.cash_entry_detail)).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription(rule.activity.getString(dev.valnook.app.R.string.nav_back)).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("wallet-month").performScrollTo().assertTextContains("2026-09")
        save("wallet-record-return-month")
        rule.onNodeWithTag("wallet-delete").performClick()
        rule.onNodeWithTag("wallet-delete-confirm").performClick()
        rule.waitUntil(10000){runBlocking{wallet.observeCards().first()}.isEmpty()}
        assertEquals(1,runBlocking{graph.overview.snapshot()}.cash.size)
        assertEquals(1,runBlocking{graph.cash.observeCashEntries(cash.id,50).first()}.size)
    }
}
