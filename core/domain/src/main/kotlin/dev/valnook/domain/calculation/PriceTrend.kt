package dev.valnook.domain.calculation

import dev.valnook.domain.model.InstrumentPricePoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What an instrument's price trend draws: one price a day, so several updates on one day never stack up. */
object PriceTrend {
    /**
     * The last price of each of the latest [days] days that have one, newest first. [newestFirst] must already be
     * ordered newest first, records at one moment newest record first, so the first price seen for a day is its last.
     */
    fun lastOfEachDay(newestFirst: List<InstrumentPricePoint>, zone: ZoneId, days: Int): List<InstrumentPricePoint> {
        if (days <= 0) return emptyList()
        val kept = ArrayList<InstrumentPricePoint>(minOf(days, newestFirst.size))
        var lastDay: LocalDate? = null
        for (point in newestFirst) {
            val day = Instant.ofEpochMilli(point.atMs).atZone(zone).toLocalDate()
            if (day == lastDay) continue
            if (kept.size == days) break
            kept += point
            lastDay = day
        }
        return kept
    }
}
