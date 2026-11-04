package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.model.Currency
import dev.valnook.feature.cash.CashEntryItem
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A cash list row shows when the entry happened, next to its note or source. */
class CashEntryItemUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(note: String) {
        val occurred = LocalDateTime.of(2026, 11, 3, 14, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entry = CashEntry(1, 1, Currency.of("CNY"), -1250, occurred, CashSource.CASH_SET, null, null, note, 1)
        rule.setContent { ValnookTheme { Box(Modifier.width(320.dp).testTag("row")) { CashEntryItem(entry) {} } } }
    }

    @Test fun note_follows_the_time() {
        show("  午饭\n和咖啡 ")
        rule.onNodeWithText("14:32 ·", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("午饭 和咖啡", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun source_follows_the_time_without_a_note() {
        show("   ")
        rule.onNodeWithText("14:32 ·", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.manual_balance_change), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun long_note_stays_on_one_line_and_leaves_the_time_whole() {
        val note = "很长的备注".repeat(40)
        show(note)
        val time = rule.onNodeWithText("14:32 ·", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shown = rule.onNodeWithText(note, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag("row").fetchSemanticsNode().boundsInRoot
        assertTrue("The note ends inside the row: $shown in $row", shown.right <= row.right + 1f)
        assertTrue("The note sits beside the time: $time and $shown", shown.left >= time.right)
        assertEquals(1, layout(note).lineCount)
    }

    @Test fun time_and_chinese_note_share_a_baseline() {
        show("午饭和咖啡")
        val time = rule.onNodeWithText("14:32 ·", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top + layout("14:32 ·").firstBaseline
        val note = rule.onNodeWithText("午饭和咖啡", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top + layout("午饭和咖啡").firstBaseline
        assertEquals(time, note, 1f)
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }
}
