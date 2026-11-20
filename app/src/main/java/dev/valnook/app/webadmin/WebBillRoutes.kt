package dev.valnook.app.webadmin

import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryPresets
import dev.valnook.domain.model.BillCounting
import dev.valnook.domain.model.BillTotals
import dev.valnook.domain.model.BillType
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.BillCounter
import dev.valnook.domain.repository.BillRepository
import dev.valnook.domain.repository.OverviewRepository
import dev.valnook.domain.repository.SettingsRepository
import dev.valnook.feature.bills.ReimbursementsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** What the bill pages read, from the database the web session uses (real or demo). */
internal class WebBillSource(val bills: BillRepository, val overview: OverviewRepository,
    val settings: SettingsRepository, val clock: Clock)

/** Which bills a list shows; each part narrows it, null meaning no limit. The same rules as the app's bill list. */
internal data class WebBillFilter(
    val type: BillType? = null,
    val categoryId: Long? = null,
    val cashAccountId: Long? = null,
    /** True: only bills counted in income and expense; false: only those left out. Transfers are neither. */
    val counted: Boolean? = null,
) {
    fun matches(bill: Bill): Boolean =
        (type == null || bill.type == type) &&
            (categoryId == null || bill.categoryId == categoryId || bill.subCategoryId == categoryId) &&
            (cashAccountId == null || bill.cashAccountId == cashAccountId || bill.toCashAccountId == cashAccountId) &&
            (counted == null || (bill.type != BillType.TRANSFER && bill.counted == counted))
}

/**
 * Read-only bill pages for the web admin. Bills are recorded and changed on the phone only: no route here writes, and
 * a write to a bill path falls through to 404. Amounts are decimal text; values in the base currency are counted by
 * [BillCounting], as the app's bill list and statistics count them.
 */
internal class WebBillRoutes(private val source: () -> WebBillSource) {

    /** The response for a bill route, or null when [path] is not one. */
    suspend fun route(method: String, path: String, query: Map<String, String>): JsonObject? {
        if (method != "GET") return null
        val s = source()
        return when {
            path == "/api/v1/bill-categories" -> categories(s)
            path == "/api/v1/bills" -> list(s, query)
            path == "/api/v1/statistics/bills" -> statistics(s, month(s, query))
            else -> BILL.matchEntire(path)?.let { detail(s, it.groupValues[1].toLong()) }
        }
    }

    private suspend fun counting(s: WebBillSource, list: List<Bill>): BillCounting =
        BillCounter(s.bills, s.settings.observeSettings(), s.overview.observeFxContext(), s.clock.zone).observe(list).first()

    /** A bad month is a format error (400) like any other bad parameter. */
    private fun month(s: WebBillSource, query: Map<String, String>): YearMonth = query["month"]?.let {
        try { YearMonth.parse(it) } catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException() }
    } ?: YearMonth.now(s.clock)

    private fun startMs(s: WebBillSource, day: LocalDate) = day.atStartOfDay(s.clock.zone).toInstant().toEpochMilli()

    private suspend fun categories(s: WebBillSource) = buildJsonObject {
        put("items", buildJsonArray { s.bills.observeCategories().first().forEach { add(category(it)) } })
    }

    private suspend fun list(s: WebBillSource, query: Map<String, String>): JsonObject {
        val month = month(s, query)
        val text = query["query"].orEmpty().trim().take(100)
        val everyMonth = query["scope"] == "all"
        val filter = WebBillFilter(
            type = query["type"]?.let { value -> BillType.valueOf(value.uppercase()).also {
                require(it == BillType.EXPENSE || it == BillType.INCOME || it == BillType.TRANSFER) } },
            categoryId = query["categoryId"]?.toLong(),
            cashAccountId = query["cashAccountId"]?.toLong(),
            counted = query["counted"]?.let { require(it == "true" || it == "false"); it == "true" })
        val monthBills = s.bills.observeMonth(month, s.clock.zone).first()
        val pool = when {
            text.isEmpty() -> monthBills
            everyMonth -> s.bills.observeSearch(text, null, null, SEARCH_LIMIT).first()
            else -> s.bills.observeSearch(text, startMs(s, month.atDay(1)), startMs(s, month.plusMonths(1).atDay(1)),
                SEARCH_LIMIT).first()
        }
        val shown = pool.filter(filter::matches)
        val counted = counting(s, monthBills)
        val before = counting(s, s.bills.observeMonth(month.minusMonths(1), s.clock.zone).first())
        val listed = counting(s, shown)
        val settings = s.settings.observeSettings().first()
        val pending = ReimbursementsViewModel.summary(s.bills.observeReimbursable().first(), settings,
            s.overview.observeFxContext().first())
        val (first, last) = monthRange(s)
        val digits = counted.base?.fraction_digits ?: 2
        return buildJsonObject {
            put("month", month.toString())
            put("baseCurrency", counted.base?.code)
            put("firstMonth", first?.toString())
            put("lastMonth", last.toString())
            put("monthCount", monthBills.size)
            put("totals", totals(counted.totals()))
            put("previous", if (before.bills.isEmpty()) JsonNull else totals(before.totals()))
            put("savings", counted.savings().let { decimal(it.value) })
            put("pending", buildJsonObject {
                put("count", pending.count); put("total", decimal(pending.pending.setScale(digits, RoundingMode.HALF_UP)))
                put("partial", pending.partial)
            })
            put("items", buildJsonArray { shown.forEach { add(bill(it, listed)) } })
            // Each listed day's own totals, of the bills the filters keep, as the app's day headers show them.
            put("days", buildJsonArray { shown.groupBy(listed::dateOf).forEach { (day, bills) -> add(buildJsonObject {
                put("date", day.toString()); put("totals", totals(listed.totals(bills)))
            }) } })
            put("cashAccounts", buildJsonArray { s.bills.observeAccountChoices().first().forEach { add(buildJsonObject {
                put("id", it.cashAccountId); put("accountId", it.accountId); put("accountName", it.accountName)
                put("name", it.cashAccountName); put("currencyCode", it.currency.code)
            }) } })
        }
    }

    /** The first month with a bill, and the later of this month and the last one with a bill (bills can be dated ahead). */
    private suspend fun monthRange(s: WebBillSource): Pair<YearMonth?, YearMonth> {
        val today = LocalDate.now(s.clock)
        val first = s.bills.observeFirstBillAt().first()?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(s.clock.zone)) }
        val latest = s.bills.observeRange(today.plusDays(1), today.plusYears(100), s.clock.zone).first()
            .maxOfOrNull { it.occurredAtMs }?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(s.clock.zone)) }
        return first to maxOf(YearMonth.from(today), latest ?: YearMonth.from(today))
    }

    private suspend fun detail(s: WebBillSource, id: Long): JsonObject? {
        val bill = s.bills.observeBill(id).first() ?: return null
        val children = s.bills.observeChildren(id).first()
        val parent = bill.parentId?.let { s.bills.observeBill(it).first() }
        val counted = counting(s, listOfNotNull(bill, parent) + children)
        return buildJsonObject {
            put("baseCurrency", counted.base?.code)
            put("bill", bill(bill, counted))
            put("children", buildJsonArray { children.forEach { add(bill(it, counted)) } })
            put("parent", parent?.let { bill(it, counted) } ?: JsonNull)
        }
    }

    private suspend fun statistics(s: WebBillSource, month: YearMonth): JsonObject {
        val now = counting(s, s.bills.observeMonth(month, s.clock.zone).first())
        val before = counting(s, s.bills.observeMonth(month.minusMonths(1), s.clock.zone).first())
        val today = LocalDate.now(s.clock)
        // Days that count toward the daily average: the whole of a past month, up to today in this one, none ahead.
        val daysSoFar = when {
            month.isBefore(YearMonth.from(today)) -> month.lengthOfMonth()
            month == YearMonth.from(today) -> today.dayOfMonth
            else -> 0
        }
        val digits = now.base?.fraction_digits ?: 2
        fun side(type: BillType) = buildJsonObject {
            val days = now.byDay(type)
            val sum = days.values.fold(BigDecimal.ZERO, BigDecimal::add)
            put("count", now.bills.count { it.type == type && now.counts(it) && (now.valueOf(it)?.signum() ?: 0) > 0 })
            put("average", decimal(if (daysSoFar == 0) BigDecimal.ZERO.setScale(digits)
                else sum.divide(BigDecimal(daysSoFar), digits, RoundingMode.HALF_UP)))
            put("days", buildJsonArray { days.toSortedMap().forEach { (day, value) -> add(buildJsonObject {
                put("date", day.toString()); put("value", decimal(value.setScale(digits, RoundingMode.HALF_UP)))
                put("count", now.bills.count { it.type == type && now.counts(it) && now.dateOf(it) == day })
            }) } })
            put("categories", buildJsonArray { now.shares(type) { it.categoryId }.forEach { share -> add(buildJsonObject {
                put("categoryId", share.categoryId); put("value", decimal(share.value.setScale(digits, RoundingMode.HALF_UP)))
                put("count", share.count)
                // The same bills again, split by subcategory; null is the part left without one.
                val subs = BillCounting(now.bills.filter { it.categoryId == share.categoryId }, now.rates, now.zone)
                    .shares(type) { it.subCategoryId }
                put("subcategories", buildJsonArray { subs.forEach { sub -> add(buildJsonObject {
                    put("categoryId", sub.categoryId); put("value", decimal(sub.value.setScale(digits, RoundingMode.HALF_UP)))
                    put("count", sub.count)
                }) } })
            }) } })
        }
        val (first, last) = monthRange(s)
        return buildJsonObject {
            put("month", month.toString())
            put("baseCurrency", now.base?.code)
            put("firstMonth", first?.toString())
            put("lastMonth", last.toString())
            put("totals", totals(now.totals()))
            put("previous", if (before.bills.isEmpty()) JsonNull else totals(before.totals()))
            put("savings", now.savings().let { buildJsonObject { put("value", decimal(it.value)); put("partial", it.partial) } })
            put("expense", side(BillType.EXPENSE))
            put("income", side(BillType.INCOME))
        }
    }

    private fun totals(value: BillTotals) = buildJsonObject {
        put("expense", decimal(value.expense)); put("income", decimal(value.income)); put("balance", decimal(value.balance))
        put("partial", value.partial); put("converted", value.converted)
    }

    private fun category(value: BillCategory) = buildJsonObject {
        put("id", value.id); put("kind", value.kind.name); put("parentId", value.parentId)
        put("icon", value.iconKey); put("color", value.colorKey); put("hidden", value.hidden); put("sortOrder", value.sortOrder)
        // Both preset names: the page shows the one for its own language; a renamed category has only its own name.
        put("customName", value.customName)
        put("nameZh", BillCategoryPresets.name(value.builtinKey, false)); put("nameEn", BillCategoryPresets.name(value.builtinKey, true))
    }

    private fun bill(value: Bill, counting: BillCounting) = buildJsonObject {
        val digits = value.currency.fraction_digits
        fun money(minor: Long, scale: Int = digits) = DecimalRules.format_units(minor, scale)
        put("id", value.id); put("revision", value.revision); put("type", value.type.name)
        put("occurredAtMs", value.occurredAtMs); put("updatedAtMs", value.updatedAtMs)
        put("currencyCode", value.currency.code)
        put("amount", money(value.amountMinor)); put("discount", money(value.discountMinor)); put("paid", money(value.paidMinor))
        put("cashAccountId", value.cashAccountId); put("accountDeleted", value.accountDeleted)
        put("accountCurrencyCode", value.accountCurrency.code)
        put("accountAmount", money(value.accountAmountMinor, value.accountCurrency.fraction_digits))
        put("rate", value.rate.stripTrailingZeros().toPlainString()); put("rateSource", value.rateSource.name)
        put("transferKind", value.transferKind?.name); put("toCashAccountId", value.toCashAccountId)
        put("toCurrencyCode", value.toCurrency?.code)
        put("toAmount", value.toAmountMinor?.let { money(it, value.toCurrency?.fraction_digits ?: digits) })
        put("fee", money(value.feeMinor, value.accountCurrency.fraction_digits))
        put("counterparty", value.counterparty)
        put("categoryId", value.categoryId); put("subCategoryId", value.subCategoryId)
        put("countInStats", value.countInStats); put("counted", value.counted)
        put("reimbursable", value.reimbursable); put("reimburseState", value.reimburseState.name)
        put("refunded", money(value.refundedMinor)); put("reimbursed", money(value.reimbursedMinor))
        put("remaining", money(value.remainingMinor))
        put("parentId", value.parentId); put("parentNote", value.parentNote)
        put("note", value.note); put("imageCount", value.imageCount)
        // What it adds to income or expense in the base currency; null for transfers and settlements, and when no
        // rate is known.
        put("value", if (!counting.counts(value)) null else counting.valueOf(value)
            ?.setScale(counting.base?.fraction_digits ?: 2, RoundingMode.HALF_UP)?.let(::decimal))
    }

    private fun decimal(value: BigDecimal): String = value.toPlainString()

    private companion object {
        val BILL = Regex("/api/v1/bills/([0-9]+)")
        /** The app's own search limit for every month. */
        const val SEARCH_LIMIT = 500
    }
}
