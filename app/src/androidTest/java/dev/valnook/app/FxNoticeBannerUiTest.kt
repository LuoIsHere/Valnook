package dev.valnook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.valnook.app.navigation.FxNoticeBanner
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.fx.FxSyncError
import dev.valnook.domain.fx.FxSyncNotice
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The pink strip for background exchange rate failures: wording per cause, one showing per notice. */
class FxNoticeBannerUiTest {
    @get:Rule val rule = createComposeRule()
    private val consumed = mutableListOf<String>()
    private var notice by mutableStateOf<FxSyncNotice?>(null)

    private fun show(value: FxSyncNotice?) {
        notice = value
        rule.setContent {
            ValnookTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Any page")
                    FxNoticeBanner(notice, { id -> consumed += id; if (notice?.id == id) notice = null })
                }
            }
        }
    }

    @Test fun network_failure_names_the_rates_in_use_and_hides_after_a_while() {
        rule.mainClock.autoAdvance = false
        show(FxSyncNotice("n1", FxSyncError.NETWORK, emptyList(), LocalDate.of(2026, 10, 30)))
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("fx-notice-banner").assertIsDisplayed()
        rule.onNodeWithText("汇率更新失败").assertExists()
        rule.onNodeWithText("网络不可用", substring = true).assertTextContains("10月30日", substring = true)
        rule.onNodeWithText("Any page").assertExists()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onAllNodesWithTag("fx-notice-banner").assertCountEquals(0)
        assertEquals(listOf("n1"), consumed)
    }

    @Test fun a_tap_hides_it_at_once_and_each_notice_shows_once() {
        show(FxSyncNotice("n1", FxSyncError.SERVICE, emptyList(), null))
        rule.onNodeWithText("汇率服务暂时不可用", substring = true).assertTextContains("暂时还没有可用的汇率", substring = true)
        rule.onNodeWithTag("fx-notice-banner").performClick()
        rule.onAllNodesWithTag("fx-notice-banner").assertCountEquals(0)
        assertEquals(listOf("n1"), consumed)
        // A later failure is a new notice and shows again.
        rule.runOnIdle { notice = FxSyncNotice("n2", FxSyncError.RATE_LIMITED, emptyList(), null) }
        rule.onNodeWithTag("fx-notice-banner").assertIsDisplayed()
    }

    @Test fun missing_currencies_and_storage_wording() {
        show(FxSyncNotice("m1", null, listOf("KWD", "TND"), LocalDate.of(2026, 10, 31)))
        rule.onNodeWithText("部分币种没有汇率").assertExists()
        rule.onNodeWithText("KWD, TND 没有可用汇率", substring = true).assertExists()
        rule.runOnIdle { notice = FxSyncNotice("s1", FxSyncError.STORAGE, emptyList(), null) }
        rule.onNodeWithText("汇率未能保存", substring = true).assertExists()
    }

    @Test fun nothing_without_a_notice() {
        show(null)
        rule.onAllNodesWithTag("fx-notice-banner").assertCountEquals(0)
    }
}
