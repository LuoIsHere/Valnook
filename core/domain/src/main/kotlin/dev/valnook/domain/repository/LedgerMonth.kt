package dev.valnook.domain.repository

import java.time.YearMonth
import java.time.ZoneId

data class LedgerMonth(val month: YearMonth, val zone: ZoneId = ZoneId.systemDefault()) {
    val startMs: Long get() = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs: Long get() = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun contains(time: Long) = time >= startMs && time < endMs
}

internal suspend fun <T, C> readMonthPage(month: LedgerMonth, cursor: C?, size: Int,
    time: (T) -> Long, nextCursor: (T) -> C, load: suspend (C?, Int) -> List<T>): List<T> {
    val limit = size.coerceIn(1,100)
    val result = mutableListOf<T>()
    var next = cursor
    while (result.size < limit) {
        val batch = load(next, limit)
        if (batch.isEmpty()) break
        result += batch.filter { month.contains(time(it)) }.take(limit-result.size)
        val last = nextCursor(batch.last())
        if (batch.size < limit || last == next) break
        next = last
    }
    return result
}
