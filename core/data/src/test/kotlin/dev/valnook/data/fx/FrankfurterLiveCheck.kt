package dev.valnook.data.fx

import dev.valnook.domain.model.Currency
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Calls the real service for every supported base. Runs only with VALNOOK_LIVE_FX=1. */
class FrankfurterLiveCheck {
    @Test fun every_supported_base_returns_rates() = runBlocking {
        assumeTrue(System.getenv("VALNOOK_LIVE_FX") == "1")
        val client = FrankfurterClient(UrlConnectionFrankfurterTransport())
        val codes = Currency.supported.map { it.code }
        val today = LocalDate.now()
        val failures = mutableListOf<String>()
        for (base in codes) {
            val quotes = codes - base
            try {
                val latest = client.rates(base, quotes, today)
                val week = client.series(base, quotes, today.minusDays(7), today)
                val missing = quotes - latest.map { it.quote }.toSet()
                val oldest = latest.minOfOrNull { it.date }
                val weekDays = week.groupBy { it.quote }.values.map { it.size }
                println("$base latest=${latest.size}/${quotes.size} oldest=$oldest missing=$missing " +
                    "week rows=${week.size} days/quote=${weekDays.minOrNull()}..${weekDays.maxOrNull()}")
                if (missing.isNotEmpty()) failures += base
            } catch (error: FrankfurterException) {
                println("$base error=${error.error} status=${error.status}")
                failures += base
            }
            Thread.sleep(300)
        }
        println("failures=$failures")
        assertTrue(failures.toString(), failures.isEmpty())
    }
}
