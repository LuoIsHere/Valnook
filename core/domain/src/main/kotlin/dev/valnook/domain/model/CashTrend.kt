package dev.valnook.domain.model

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CashTrendPoint(val date: LocalDate, val balanceMinor: BigInteger?)
data class CashTrendChange(val occurredAtMs: Long, val deltaMinor: Long)

object CashTrendRules {
    fun dates(now: Instant, zone: ZoneId): List<LocalDate> {
        val today = now.atZone(zone).toLocalDate()
        return generateSequence(today.minusMonths(3)) { it.plusDays(1) }.takeWhile { it <= today }.toList()
    }
    fun calculate(balance: Long, changes: List<CashTrendChange>, reliableFromMs: Long,
        now: Instant, zone: ZoneId): List<CashTrendPoint> {
        // Reverse each effective entry once; future-dated entries remain in today's live ledger balance.
        // 每条有效记录只回退一次；今天使用账面余额，历史日末扣除之后的记录（包括未来日期记录）。
        val dates = dates(now, zone)
        val ordered = changes.sortedByDescending { it.occurredAtMs }
        var cursor = 0
        var historical = BigInteger.valueOf(balance)
        val result = mutableListOf<CashTrendPoint>()
        dates.asReversed().forEachIndexed { index, date ->
            val cutoff = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            if (index > 0) {
                while (cursor < ordered.size && ordered[cursor].occurredAtMs > cutoff) {
                    historical -= BigInteger.valueOf(ordered[cursor++].deltaMinor)
                }
            }
            // Pre-history is displayed as zero; this chart-only padding never creates ledger entries.
            // 历史起点之前按零绘图，仅补齐图表序列，不创建账本记录。
            result += CashTrendPoint(date, if (index > 0 && cutoff < reliableFromMs) BigInteger.ZERO else historical)
        }
        return result.asReversed()
    }
}
