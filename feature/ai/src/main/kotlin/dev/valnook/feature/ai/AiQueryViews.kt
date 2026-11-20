package dev.valnook.feature.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.Space
import dev.valnook.domain.ai.AiQueryCall
import dev.valnook.domain.ai.AiQueryGroup
import dev.valnook.domain.ai.AiQueryRecord
import dev.valnook.domain.ai.AiQuerySort
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.BillAccountChoice
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the statistics show what an answer looked up. */
sealed interface AiStatsTarget {
    /** A category's page for [month]; a sub-category opens its parent's. */
    data class Category(val categoryId: Long, val expense: Boolean, val month: YearMonth) : AiStatsTarget
    data class Day(val date: LocalDate) : AiStatsTarget
    data class Month(val expense: Boolean, val month: YearMonth) : AiStatsTarget
}

/**
 * Where an answer's lookups can be seen in the statistics, which go by month: the latest query that has a place
 * there, a day's bills, a category's month, or the expense or income of a month. A range across months, or a list
 * of both expense and income, has none.
 */
fun aiStatsTarget(records: List<AiQueryRecord>, categories: Map<Long, BillCategory>): AiStatsTarget? =
    records.asReversed().firstNotNullOfOrNull { record -> record.call?.takeIf { !record.error }?.let { statsTarget(it, categories) } }

private fun statsTarget(call: AiQueryCall, categories: Map<Long, BillCategory>): AiStatsTarget? {
    if (call.from == call.to) return AiStatsTarget.Day(call.from)
    val month = YearMonth.from(call.from)
    if (YearMonth.from(call.to) != month) return null
    val category = call.categoryId?.let(categories::get)
    val expense = when (call.type) {
        BillType.EXPENSE -> true
        BillType.INCOME -> false
        else -> category?.let { it.kind == BillCategoryKind.EXPENSE }
    } ?: return null
    return if (category != null) AiStatsTarget.Category(category.parentId ?: category.id, expense, month)
    else AiStatsTarget.Month(expense, month)
}

/**
 * A query's range as people say it: a day, a month, a run of whole months, or two days; the year only when it is not
 * [today]'s.
 */
fun aiQueryRange(from: LocalDate, to: LocalDate, today: LocalDate, english: Boolean): String {
    val showYear = from.year != today.year || to.year != today.year
    fun pattern(zh: String, en: String) = DateTimeFormatter.ofPattern(if (english) en else zh, if (english) Locale.ENGLISH else Locale.CHINA)
    val day = if (showYear) pattern("yyyy 年 M 月 d 日", "MMM d, yyyy") else pattern("M 月 d 日", "MMM d")
    val month = if (showYear) pattern("yyyy 年 M 月", "MMMM yyyy") else pattern("M 月", "MMMM")
    val wholeMonths = from.dayOfMonth == 1 && to == YearMonth.from(to).atEndOfMonth()
    return when {
        from == to -> from.format(day)
        wholeMonths && YearMonth.from(from) == YearMonth.from(to) -> from.format(month)
        wholeMonths -> from.format(month) + " – " + to.format(month)
        else -> from.format(day) + " – " + to.format(day)
    }
}

/** What a query looked up, such as "11 月支出 · 按分类 · 餐饮". */
@Composable
fun aiQueryText(call: AiQueryCall, categories: Map<Long, BillCategory>, accounts: Map<Long, BillAccountChoice>): String {
    val english = LocalConfiguration.current.locales[0].language != "zh"
    val range = aiQueryRange(call.from, call.to, LocalDate.now(), english)
    val kind = stringResource(when (call.type) {
        BillType.EXPENSE -> R.string.ai_query_expense
        BillType.INCOME -> R.string.ai_query_income
        else -> R.string.ai_query_all_bills
    })
    val what = if (call.summary) kind else stringResource(R.string.ai_query_bills, kind)
    val parts = mutableListOf(range + (if (english) " " else "") + what)
    if (call.summary) when (call.group) {
        AiQueryGroup.NONE -> null
        AiQueryGroup.CATEGORY -> R.string.ai_query_by_category
        AiQueryGroup.SUB_CATEGORY -> R.string.ai_query_by_sub_category
        AiQueryGroup.MONTH -> R.string.ai_query_by_month
        AiQueryGroup.MONTH_CATEGORY -> R.string.ai_query_by_month_category
        AiQueryGroup.DAY -> R.string.ai_query_by_day
        AiQueryGroup.ACCOUNT -> R.string.ai_query_by_account
    }?.let { parts += stringResource(it) }
    if (!call.summary && call.sort == AiQuerySort.AMOUNT_DESC) parts += stringResource(R.string.ai_query_by_amount)
    call.categoryId?.let { id -> categories[id]?.name(english)?.takeIf { it.isNotBlank() }?.let(parts::add) }
    call.accountId?.let { id -> accounts[id]?.let { it.cashAccountName.ifBlank { it.accountName } }?.let(parts::add) }
    if (call.keyword.isNotEmpty()) parts += stringResource(R.string.ai_query_keyword, call.keyword)
    if (call.shortened) parts += stringResource(R.string.ai_query_shortened)
    return parts.joinToString(" · ")
}

/** The line under the working dots: what the answer is waiting on. */
@Composable
fun AiQueryingLine(call: AiQueryCall, categories: Map<Long, BillCategory>, accounts: Map<Long, BillAccountChoice>) {
    val text = stringResource(R.string.ai_chat_querying, aiQueryText(call, categories, accounts))
    AnimatedContent(text, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "querying") { shown ->
        Text(shown, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("ai-chat-querying"))
    }
}

/** What an answer looked up, folded under one line until opened: each query and what it gave. */
@Composable
fun AiQueryProcess(messageId: Long, records: List<AiQueryRecord>, categories: Map<Long, BillCategory>,
    accounts: Map<Long, BillAccountChoice>) {
    var open by rememberSaveable(messageId) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = !open }
        .testTag("ai-chat-queries-$messageId").padding(vertical = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.ai_query_process, records.size, records.size),
                style = MaterialTheme.typography.labelMedium, color = muted)
            FaIcon(if (open) FaIcons.chevronDown else FaIcons.chevronRight, null, Modifier.padding(start = Space.xs).size(12.dp), muted)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
            Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                records.forEachIndexed { index, record ->
                    val what = record.call?.let { aiQueryText(it, categories, accounts) } ?: stringResource(R.string.ai_query_unreadable)
                    val result = when {
                        record.error -> stringResource(R.string.ai_query_refused)
                        record.call?.summary == false -> pluralStringResource(R.plurals.ai_query_bill_rows, record.rows, record.rows)
                        else -> pluralStringResource(R.plurals.ai_query_rows, record.rows, record.rows)
                    }
                    Text("${index + 1}. $what — $result", style = MaterialTheme.typography.bodySmall,
                        color = if (record.error) MaterialTheme.colorScheme.error else muted,
                        modifier = Modifier.testTag("ai-chat-query-$messageId-$index"))
                }
            }
        }
    }
}
