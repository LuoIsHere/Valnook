package dev.valnook.domain

import dev.valnook.domain.calculation.PriceTrend
import dev.valnook.domain.model.InstrumentPricePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PriceTrendTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private fun at(day: Int, time: String) = LocalDateTime.parse("2026-10-%02dT%s".format(day, time)).atZone(zone).toInstant().toEpochMilli()
    private fun p(day: Int, time: String, price: Long) = InstrumentPricePoint(at(day, time), price)

    @Test fun several_updates_on_one_day_keep_only_the_last() {
        val prices = listOf(p(3, "21:00", 130), p(3, "15:00", 120), p(3, "09:30", 110), p(2, "16:00", 100), p(2, "10:00", 90))
        assertEquals(listOf(p(3, "21:00", 130), p(2, "16:00", 100)), PriceTrend.lastOfEachDay(prices, zone, 30))
    }

    @Test fun updates_at_one_moment_keep_the_newest_record() {
        // Newest first, so the later record at the same moment comes first.
        val prices = listOf(p(3, "12:00", 125), p(3, "12:00", 120))
        assertEquals(listOf(p(3, "12:00", 125)), PriceTrend.lastOfEachDay(prices, zone, 30))
    }

    @Test fun the_day_is_the_local_one() {
        // 23:59 and 00:01 local are two days, though both fall on one UTC day.
        val prices = listOf(p(4, "00:01", 120), p(3, "23:59", 110), p(3, "07:00", 100))
        assertEquals(listOf(p(4, "00:01", 120), p(3, "23:59", 110)), PriceTrend.lastOfEachDay(prices, zone, 30))
        // In UTC the first two share 3 October; the 07:00 update is 2 October there.
        val utc = PriceTrend.lastOfEachDay(prices, ZoneId.of("UTC"), 30)
        assertEquals(listOf(p(4, "00:01", 120), p(3, "07:00", 100)), utc)
    }

    @Test fun the_limit_counts_days_not_updates() {
        val prices = (10 downTo 1).flatMap { day -> listOf(p(day, "18:00", day * 10L + 1), p(day, "09:00", day * 10L)) }
        val trend = PriceTrend.lastOfEachDay(prices, zone, 3)
        assertEquals(listOf(p(10, "18:00", 101), p(9, "18:00", 91), p(8, "18:00", 81)), trend)
        assertEquals(emptyList<InstrumentPricePoint>(), PriceTrend.lastOfEachDay(prices, zone, 0))
        assertEquals(emptyList<InstrumentPricePoint>(), PriceTrend.lastOfEachDay(emptyList(), zone, 30))
    }
}
