package dev.valnook.domain.model

import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class CashTrendPoint(val month: YearMonth, val balanceMinor: BigInteger?)
data class CashTrendChange(val occurredAtMs: Long, val deltaMinor: Long)

object CashTrendRules {
    fun months(now: Instant, zone: ZoneId): List<YearMonth> {
        val current = YearMonth.from(now.atZone(zone))
        return (5L downTo 0L).map(current::minusMonths)
    }
    fun calculate(balance: Long, changes: List<CashTrendChange>, reliableFromMs: Long,
        now: Instant, zone: ZoneId): List<CashTrendPoint> {
        // Historical month-end points reverse future-dated entries too; the last point is the live ledger balance.
        // 历史月末需扣除未来日期记录；本月最后一点使用账面余额，与账户摘要一致。
        val months = months(now, zone)
        val cutoffs = months.mapIndexed { index, month ->
            if (index == months.lastIndex) now.toEpochMilli()
            else month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
        val deductions = Array(6) { BigInteger.ZERO }
        changes.forEach { change -> cutoffs.forEachIndexed { index, cutoff ->
            if (index < months.lastIndex && change.occurredAtMs > cutoff) deductions[index] += BigInteger.valueOf(change.deltaMinor)
        } }
        return months.mapIndexed { index, month -> CashTrendPoint(month,
            if (index < months.lastIndex && cutoffs[index] < reliableFromMs) null else BigInteger.valueOf(balance) - deductions[index]) }
    }
}
