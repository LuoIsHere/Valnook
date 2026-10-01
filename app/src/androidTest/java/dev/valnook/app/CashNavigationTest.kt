package dev.valnook.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.DatePicker
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppGraph
import dev.valnook.domain.repository.SetCashBalance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

@HiltAndroidTest
class CashNavigationTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Inject lateinit var graph:AppGraph
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private var keyboard_setting:String?=null
    private var accessibility_flags:Int?=null

    @Before fun seed() {
        val original=shell("settings get secure show_ime_with_hard_keyboard").trim()
        require(original in listOf("0","1","null"))
        keyboard_setting=original
        shell("settings put secure show_ime_with_hard_keyboard 1")
        hilt.inject()
        runBlocking {
            val account=graph.accounts.save_account(null,"返回手势测试","")
            graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(),account,"USD",10000,null))
        }
    }
    @After fun restore_keyboard() {
        accessibility_flags?.let{flags->automation.serviceInfo=automation.serviceInfo.apply{this.flags=flags}}
        keyboard_setting?.let {
            shell(if(it=="null")"settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $it")
        }
    }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use{it.readText()}

    @Test fun system_back_has_no_preview_and_preserves_keyboard_and_dialog_priority() {
        wait_text("返回手势测试")
        rule.onNodeWithText("返回手势测试").performClick()
        wait_text("USD · 美元")
        screenshot("topbar-transparent")
        rule.onNodeWithContentDescription("余额变化 · USD").performClick()
        wait_text("手动余额调整")
        rule.onNodeWithText("手动余额调整").performClick()
        wait_text("流水详情")
        rule.waitForIdle()
        SystemClock.sleep(250)
        val before=screenshot("back-gesture-before")
        val width=before.width.toFloat();val y=before.height*0.5f
        val down=SystemClock.uptimeMillis()
        touch(down,MotionEvent.ACTION_DOWN,1f,y)
        for(step in 1..10)touch(down,MotionEvent.ACTION_MOVE,width*0.035f*step,y)
        SystemClock.sleep(250)
        val during=screenshot("back-gesture-held")
        try {
            // Exclude system bars and the edge arrow; the page body must stay still.
            var sampled=0;var changed=0
            for(x in before.width/2 until before.width*9/10 step 3) {
                for(row in before.height/5 until before.height*3/4 step 3) {
                    val a=before.getPixel(x,row);val b=during.getPixel(x,row)
                    sampled++
                    if(kotlin.math.abs(Color.red(a)-Color.red(b))>12 ||
                        kotlin.math.abs(Color.green(a)-Color.green(b))>12 ||
                        kotlin.math.abs(Color.blue(a)-Color.blue(b))>12)changed++
                }
            }
            assertTrue("Page changed during predictive gesture: $changed/$sampled",changed.toDouble()/sampled<0.01)
        } finally {
            for(step in 9 downTo 0)touch(down,MotionEvent.ACTION_MOVE,(width*0.035f*step).coerceAtLeast(1f),y)
            touch(down,MotionEvent.ACTION_UP,1f,y)
            before.recycle();during.recycle()
        }
        rule.waitForIdle()
        rule.onNodeWithText("流水详情").assertIsDisplayed()

        back_gesture(width,y)
        wait_text("余额变化")
        rule.onNodeWithText("流水详情").assertDoesNotExist()

        rule.onNodeWithText("手动余额调整").performClick()
        wait_text("流水详情")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("修改余额变化"))
        rule.onNodeWithText("修改余额变化").performClick()
        wait_text("增减方向")
        rule.onNode(hasSetTextAction() and hasText("变化金额")).performClick()
        rule.waitUntil(5000){ime_visible()}
        // Insets change before the platform IME finishes opening/registering back.
        SystemClock.sleep(400)
        assertTrue(ime_visible())
        show_screen_keyboard()
        screenshot("keyboard-before-back").recycle()
        back_gesture(width,y)
        rule.waitUntil(5000){!ime_visible()}
        SystemClock.sleep(300)
        screenshot("keyboard-after-back").recycle()
        rule.onNodeWithText("增减方向").assertExists()

        rule.onNodeWithContentDescription("记账日期:",substring=true).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).check(matches(isDisplayed()))
        pressBack()
        onView(isAssignableFrom(DatePicker::class.java)).check(doesNotExist())
        rule.onNodeWithText("增减方向").assertExists()
        pressBack()
        wait_text("流水详情")
    }

    private fun show_screen_keyboard() {
        // The Pixel's hardware-keyboard toolbar reports IME visibility even when
        // no on-screen keyboard is open. Expand it before testing keyboard back.
        val info=automation.serviceInfo
        accessibility_flags=info.flags
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=info
        fun find_action(node:android.view.accessibility.AccessibilityNodeInfo?,label:String):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.contentDescription?.toString()==label||node.text?.toString()==label)return node
            for(index in 0 until node.childCount)find_action(node.getChild(index),label)?.let{return it}
            return null
        }
        val toolbar=automation.windows.firstNotNullOfOrNull{find_action(it.root,"Open more keyboard options")}
        if(toolbar!=null) {
            assertTrue(toolbar.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(300)
            val show=automation.windows.firstNotNullOfOrNull{find_action(it.root,"Show on-screen keyboard")}
            assertNotNull("Expected Pixel keyboard expansion action",show)
            assertTrue(show!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        rule.waitUntil(5000){rule.runOnUiThread {
            val height=ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            height>150*rule.activity.resources.displayMetrics.density
        }}
        SystemClock.sleep(300)
    }

    private fun wait_text(text:String)=rule.waitUntil(15000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun ime_visible():Boolean=rule.runOnUiThread {
        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
    }
    private fun touch(down:Long,action:Int,x:Float,y:Float) {
        SystemClock.sleep(35)
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        event.source=InputDevice.SOURCE_TOUCHSCREEN
        try {assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
    }
    private fun back_gesture(width:Float,y:Float) {
        val down=SystemClock.uptimeMillis()
        touch(down,MotionEvent.ACTION_DOWN,1f,y)
        for(step in 1..10)touch(down,MotionEvent.ACTION_MOVE,width*0.04f*step,y)
        touch(down,MotionEvent.ACTION_UP,width*0.4f,y)
    }
    private fun screenshot(name:String):Bitmap {
        val bitmap=requireNotNull(automation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        return bitmap
    }
}
