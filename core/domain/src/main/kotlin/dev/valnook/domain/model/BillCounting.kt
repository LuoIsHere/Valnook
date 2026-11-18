package dev.valnook.domain.model

import dev.valnook.domain.fx.FxRateResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A rate into the base currency (1 currency = [rate] base) and the day it was stored for; [day] null when today's rate stood in. */
data class BillDayRate(val rate: BigDecimal, val day: LocalDate?)

/**
 * The rates bills count at in income and expense: the rate stored for the bill's own day, else today's rate
 * ([FxRateResolver.today]); without either the bill is left out and its total marked incomplete. Net worth
 * statistics value each day by the same rule.
 *
 * [stored] holds stored rates by currency and day, already as 1 currency = value base.
 */
class BillDayRates(val today: FxRateResolver, private val stored: Map<String, Map<LocalDate, BigDecimal>> = emptyMap()) {
    val base: Currency? get() = today.base

    /** The rate [code] counts at on [date]; the base currency itself is always 1, with no rate looked up. */
    fun on(code: String, date: LocalDate): BillDayRate? {
        val base = base ?: return null
        if (code == base.code) return BillDayRate(BigDecimal.ONE, date)
        stored[code]?.get(date)?.let { return BillDayRate(it, date) }
        return today.today(code)?.let { BillDayRate(it, null) }
    }
}

/** Expense and income in the base currency; [partial] when some bill had no rate and was left out, [converted] when some bill was in another currency. */
data class BillTotals(val expense: BigDecimal, val income: BigDecimal, val partial: Boolean, val converted: Boolean) {
    val balance: BigDecimal get() = income - expense
}

/** One group's part of a total, unrounded in the base currency; [count] bills make it up. */
data class BillShare(val categoryId: Long?, val value: BigDecimal, val count: Int)

/** Discounts in the base currency; [partial] when some had no rate and were left out. */
data class BillSavings(val value: BigDecimal, val partial: Boolean)

/**
 * [bills] as income and expense count them, each at [rates] on its own date in [zone]. Statistics, the bill list's
 * totals and AI questions about spending all count through here, so they agree.
 *
 * - Only expenses and income that are [Bill.counted] count. Transfers, refunds and reimbursements never do.
 * - An expense counts what it still costs: its account amount scaled by [Bill.remainingMinor] of [Bill.paidMinor].
 *   Its refunds and reimbursements come off it in the month it was spent, whenever they arrived.
 * - A bill whose account was deleted still counts: it keeps its own currency and amount.
 */
class BillCounting(val bills: List<Bill>, val rates: BillDayRates, val zone: ZoneId) {
    val base: Currency? get() = rates.base

    fun dateOf(bill: Bill): LocalDate = Instant.ofEpochMilli(bill.occurredAtMs).atZone(zone).toLocalDate()

    /** Whether [bill] goes into expense or income at all. */
    fun counts(bill: Bill): Boolean = bill.counted && (bill.type == BillType.EXPENSE || bill.type == BillType.INCOME)

    /**
     * What [bill] comes to in the base currency, unrounded: an expense net of refunds and reimbursements. Null when
     * no rate is known for its account currency. Worked out for bills that do not count too, to show them.
     */
    fun valueOf(bill: Bill): BigDecimal? {
        val rate = rates.on(bill.accountCurrency.code, dateOf(bill)) ?: return null
        val value = BigDecimal.valueOf(bill.accountAmountMinor, bill.accountCurrency.fraction_digits).multiply(rate.rate)
        return if (bill.type == BillType.EXPENSE) net(bill, value) else value
    }

    /** Expense and income of the bills in [list] that count, rounded to the base currency's digits. */
    fun totals(list: List<Bill> = bills): BillTotals {
        var expense = BigDecimal.ZERO
        var income = BigDecimal.ZERO
        var partial = false
        var converted = false
        list.filter(::counts).forEach { bill ->
            val value = valueOf(bill)
            if (value == null) { partial = true; return@forEach }
            if (bill.accountCurrency != base) converted = true
            if (bill.type == BillType.EXPENSE) expense += value else income += value
        }
        val digits = base?.fraction_digits ?: 2
        return BillTotals(expense.setScale(digits, RoundingMode.HALF_UP), income.setScale(digits, RoundingMode.HALF_UP),
            partial, converted)
    }

    /**
     * The counted bills of [type] grouped by [key] (a category, or null for bills without one), largest first. Only
     * bills that come to more than nothing count toward [BillShare.count]: one refunded in full is not spending.
     * Bills without a rate are left out, as from [totals].
     */
    fun shares(type: BillType, key: (Bill) -> Long?): List<BillShare> =
        bills.asSequence().filter { it.type == type && counts(it) }
            .mapNotNull { bill -> valueOf(bill)?.takeIf { it.signum() > 0 }?.let { key(bill) to it } }
            .groupBy({ it.first }, { it.second })
            .map { (id, values) -> BillShare(id, values.fold(BigDecimal.ZERO, BigDecimal::add), values.size) }
            .sortedWith(compareByDescending<BillShare> { it.value }.thenBy { it.categoryId ?: Long.MAX_VALUE })

    /** The counted bills of [type] summed by date; days without any are absent. */
    fun byDay(type: BillType): Map<LocalDate, BigDecimal> =
        bills.asSequence().filter { it.type == type && counts(it) }
            .mapNotNull { bill -> valueOf(bill)?.let { dateOf(bill) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.fold(BigDecimal.ZERO, BigDecimal::add) }

    /**
     * What the counted expenses saved through discounts, in the base currency. A discount is in the bill's own
     * currency, so it converts at that currency's rate on the bill's day; one without a rate is left out.
     */
    fun savings(): BillSavings {
        var total = BigDecimal.ZERO
        var partial = false
        bills.filter { it.type == BillType.EXPENSE && counts(it) && it.discountMinor > 0 }.forEach { bill ->
            val rate = rates.on(bill.currency.code, dateOf(bill))
            if (rate == null) { partial = true; return@forEach }
            total += BigDecimal.valueOf(bill.discountMinor, bill.currency.fraction_digits).multiply(rate.rate)
        }
        return BillSavings(total.setScale(base?.fraction_digits ?: 2, RoundingMode.HALF_UP), partial)
    }

    companion object {
        /** [value] of what [bill] paid, scaled to what is left of it after refunds and reimbursements. */
        fun net(bill: Bill, value: BigDecimal): BigDecimal =
            if (bill.paidMinor <= 0 || bill.remainingMinor == bill.paidMinor) value
            else value.multiply(BigDecimal.valueOf(bill.remainingMinor.coerceAtLeast(0)))
                .divide(BigDecimal.valueOf(bill.paidMinor), 12, RoundingMode.HALF_UP)
    }
}
