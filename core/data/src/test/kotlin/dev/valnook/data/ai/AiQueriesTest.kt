package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiQueryCall
import dev.valnook.domain.ai.AiQueryGroup
import dev.valnook.domain.ai.AiQuerySort
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** The query tools count as the statistics page does, and check what the model asks for. Synthetic data only. */
class AiQueriesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val jpy = Currency.of("JPY")
    private val rates = BillDayRates(
        FxRateResolver(AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.0")))), FxContext()),
        mapOf("USD" to mapOf(LocalDate.parse("2026-11-02") to BigDecimal("7.2"))))

    private fun category(id: Long, kind: BillCategoryKind, parent: Long?, name: String, hidden: Boolean = false) =
        BillCategory(id, kind, parent, null, name, "icon", "grey", id, hidden, null, 1)
    private val categories = listOf(
        category(1, BillCategoryKind.EXPENSE, null, "Food"), category(11, BillCategoryKind.EXPENSE, 1, "Lunch"),
        category(2, BillCategoryKind.EXPENSE, null, "Travel"), category(3, BillCategoryKind.INCOME, null, "Pay"))
    private val accounts = listOf(AiAccountChoice(7, "Synthetic card", "CNY", favorite = true))

    private var nextId = 1L
    private fun bill(type: BillType, amount: Long, on: String, category: Long, sub: Long? = null, currency: Currency = cny,
        account: Long? = 7, note: String = "", refunded: Long = 0, counted: Boolean = true) = Bill(
        id = nextId++, type = type, occurredAtMs = LocalDate.parse(on).atTime(12, 30).atZone(zone).toInstant().toEpochMilli(),
        amountMinor = amount, currency = currency, discountMinor = 0, cashAccountId = account, accountCurrency = currency,
        accountAmountMinor = amount, rate = BigDecimal.ONE, rateSource = BillRateSource.SAME, transferKind = null,
        toCashAccountId = null, toCurrency = null, toAmountMinor = null, feeMinor = 0, counterparty = "",
        categoryId = category, subCategoryId = sub, countInStats = counted, reimbursable = false,
        reimburseState = ReimburseState.NONE, parentId = null, note = note, revision = 1, createdAtMs = 0, updatedAtMs = 0,
        refundedMinor = refunded)

    /** A synthetic November: lunches, a refunded trip, a dollar bill, one without a rate, one not counted, pay. */
    private val november = listOf(
        bill(BillType.EXPENSE, 3_500, "2026-11-01", 1, 11, note = "Team lunch"),
        bill(BillType.EXPENSE, 4_200, "2026-11-02", 1, 11, note = "lunch"),
        bill(BillType.EXPENSE, 1_000, "2026-11-02", 1, currency = usd, note = "Coffee beans"),
        bill(BillType.EXPENSE, 50_000, "2026-11-08", 2, refunded = 20_000, note = "Train"),
        bill(BillType.EXPENSE, 9_999, "2026-11-08", 2, refunded = 9_999, note = "Cancelled hotel"),
        bill(BillType.EXPENSE, 800, "2026-11-09", 2, currency = jpy, account = 8),
        bill(BillType.EXPENSE, 7_000, "2026-11-10", 1, counted = false),
        bill(BillType.EXPENSE, 2_000, "2026-11-12", 1, account = null),
        bill(BillType.INCOME, 1_000_000, "2026-11-15", 3),
    )
    private val counting = BillCounting(november, rates, zone)
    private val runner = AiQueryRunner(categories, accounts, english = true)
    private val reader = AiQueryReader(categories, accounts)
    private val from = LocalDate.parse("2026-11-01")
    private val to = LocalDate.parse("2026-11-30")

    private fun summary(group: AiQueryGroup, type: BillType = BillType.EXPENSE, category: Long? = null) =
        runner.run(AiQueryCall(true, type, from, to, group, categoryId = category), counting)

    private fun money(value: BigDecimal) = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    @Test fun totals_categories_and_days_match_the_statistics_page() {
        // What the statistics page shows for the month comes from these same calls.
        val total = counting.totals().expense
        assertTrue(summary(AiQueryGroup.NONE).text.contains("Total: ${money(total)} ("))
        val byCategory = summary(AiQueryGroup.CATEGORY).text
        counting.shares(BillType.EXPENSE) { it.categoryId }.forEach { share ->
            assertTrue(byCategory, byCategory.contains("${share.categoryId} ${categories.first { it.id == share.categoryId }.name(true)} | ${money(share.value)} | ${share.count}"))
        }
        val byDay = summary(AiQueryGroup.DAY).text
        counting.byDay(BillType.EXPENSE).forEach { (day, value) -> assertTrue(byDay, byDay.contains("$day | ${money(value)} |")) }
        // Days come in order; the refunded trip counts what is left of it, the fully refunded hotel not at all.
        assertTrue(byDay.indexOf("2026-11-01") < byDay.indexOf("2026-11-12"))
        assertTrue(byDay.contains("2026-11-08 | 300.00 | 1"))
        // The bill without a rate is left out and said so; the uncounted one is not there at all.
        assertTrue(byDay.contains("1 bill(s) left out: no exchange rate"))
        assertFalse(byDay.contains("2026-11-10"))
    }

    @Test fun groups_by_sub_category_account_and_month() {
        val sub = summary(AiQueryGroup.SUB_CATEGORY).text
        assertTrue(sub, sub.contains("11 Lunch | 77.00 | 2"))
        assertTrue(sub, sub.contains("1 Food (no sub-category) | 92.00 | 2"))
        val account = summary(AiQueryGroup.ACCOUNT).text
        assertTrue(account, account.contains("7 Synthetic card |"))
        assertTrue(account, account.contains("no account | 20.00 | 1"))
        // An account the request did not send is never named.
        val other = runner.run(AiQueryCall(true, BillType.EXPENSE, from, to, AiQueryGroup.ACCOUNT),
            BillCounting(listOf(bill(BillType.EXPENSE, 1_500, "2026-11-03", 2, account = 8)), rates, zone)).text
        assertTrue(other, other.contains("other accounts | 15.00 | 1"))
        val month = runner.run(AiQueryCall(true, BillType.INCOME, from, to, AiQueryGroup.MONTH), counting).text
        assertTrue(month, month.contains("2026-11 | 10000.00 | 1"))
        // A parent category filter takes its sub-categories along.
        assertTrue(summary(AiQueryGroup.NONE, category = 1).text.contains("Total: 169.00 (4 bills)"))
    }

    @Test fun bill_lists_sort_limit_and_show_both_amounts() {
        val newest = runner.run(AiQueryCall(false, null, from, to, limit = 3), counting)
        assertEquals(3, newest.rows)
        assertTrue(newest.text, newest.text.contains("8 match, showing 3 (newest first)"))
        assertTrue(newest.text.lineSequence().drop(2).first().startsWith("2026-11-15 12:30 | INCOME | 3 Pay"))
        val largest = runner.run(AiQueryCall(false, BillType.EXPENSE, from, to, sort = AiQuerySort.AMOUNT_DESC), counting).text
        assertTrue(largest, largest.lineSequence().drop(2).first().contains("| 2 Travel | 500.00 CNY | 300.00 | Train"))
        assertTrue(largest, largest.contains("| 1 Food | 10.00 USD | 72.00 | Coffee beans"))
        assertTrue(largest, largest.contains("| 800 JPY | no rate |"))
        val keyword = runner.run(AiQueryCall(false, null, from, to, keyword = "LUNCH"), counting)
        assertEquals(2, keyword.rows)
        assertTrue(runner.run(AiQueryCall(false, null, from, from, keyword = "nothing like this"), counting).text.contains("No bills match."))
    }

    @Test fun the_reader_checks_ids_values_and_ranges() {
        val call = reader.read(AiQueryTools.SUMMARY, """{"bill_type":"expense","from":"2026-11-30","to":"2026-11-01","group_by":"day","category_id":11}""")
        assertEquals(AiQueryCall(true, BillType.EXPENSE, from, to, AiQueryGroup.DAY, categoryId = 11), call)
        // A month stands for its days; a range past 13 months keeps its last 13.
        val long = reader.read(AiQueryTools.BILLS, """{"from":"2024-01","to":"2026-11","limit":99,"sort":"AMOUNT_DESC"}""")
        assertEquals(LocalDate.parse("2025-11-01"), long.from)
        assertEquals(to, long.to)
        assertTrue(long.shortened)
        assertEquals(20, long.limit)
        assertTrue(runner.run(long, counting).text.contains("The range was cut to 13 months"))
        fun error(name: String, arguments: String) = try { reader.read(name, arguments); null } catch (e: AiQueryError) { e.message }
        assertEquals("bill_type is required: EXPENSE or INCOME.", error(AiQueryTools.SUMMARY, """{"from":"2026-11-01","to":"2026-11-30"}"""))
        assertEquals("category_id 99 is not a listed category.", error(AiQueryTools.BILLS, """{"from":"2026-11-01","to":"2026-11-30","category_id":99}"""))
        assertEquals("category_id 3 is not an expense category.", error(AiQueryTools.SUMMARY, """{"bill_type":"EXPENSE","from":"2026-11-01","to":"2026-11-30","category_id":3}"""))
        assertEquals("account_id 8 is not a listed account.", error(AiQueryTools.BILLS, """{"from":"2026-11-01","to":"2026-11-30","account_id":8}"""))
        assertNotNull(error(AiQueryTools.SUMMARY, """{"bill_type":"EXPENSE","from":"last week","to":"2026-11-30"}"""))
        assertNotNull(error(AiQueryTools.SUMMARY, """{"bill_type":"EXPENSE","from":"2026-11-01","to":"2026-11-30","group_by":"YEAR"}"""))
        assertNotNull(error(AiQueryTools.SUMMARY, "not json"))
    }

    @Test fun summary_rows_stop_at_the_limit() {
        val many = (1..70).map { bill(BillType.EXPENSE, 100L * it, "2026-01-01", 1, note = "n$it") }
        val counted = BillCounting(many.mapIndexed { i, b -> b.copy(occurredAtMs = LocalDate.parse("2026-01-01").plusDays(i.toLong())
            .atTime(9, 0).atZone(zone).toInstant().toEpochMilli()) }, rates, zone)
        val result = runner.run(AiQueryCall(true, BillType.EXPENSE, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
            AiQueryGroup.DAY), counted)
        assertEquals(60, result.rows)
        assertTrue(result.text.contains("10 more rows not shown."))
        assertTrue(BillCounting(emptyList(), BillDayRates(FxRateResolver(AppSettings(), FxContext())), zone)
            .let { runner.run(AiQueryCall(true, BillType.EXPENSE, from, to), it) }.text.contains("No base currency"))
    }
}
