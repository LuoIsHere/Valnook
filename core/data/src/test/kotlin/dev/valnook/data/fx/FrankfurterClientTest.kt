package dev.valnook.data.fx

import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrankfurterClientTest {
    private class FakeTransport(private val reply: (String) -> FrankfurterResponse) : FrankfurterTransport {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): FrankfurterResponse { urls += url; return reply(url) }
    }

    private fun ok(json: String) = FrankfurterResponse(200, json.toByteArray())
    private fun row(date: String, quote: String, rate: String, base: String = "CNY") =
        """{"date":"$date","base":"$base","quote":"$quote","rate":$rate}"""
    private fun day(value: String) = LocalDate.parse(value)

    private suspend fun expectError(expected: FrankfurterError, status: Int? = null, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (error: FrankfurterException) {
            assertEquals(expected, error.error)
            assertEquals(status, error.status)
        }
    }

    @Test fun single_day_request_uses_the_constant_address_and_sorted_quotes() = runTest {
        val transport = FakeTransport { ok("[${row("2026-10-24", "JPY", "23.561")},${row("2026-10-24", "USD", "0.15001")}]") }
        val rates = FrankfurterClient(transport).rates("CNY", listOf("USD", "JPY"), day("2026-10-24"))
        assertEquals(listOf("https://api.frankfurter.dev/v2/rates?base=CNY&quotes=JPY,USD&date=2026-10-24"), transport.urls)
        assertEquals(listOf(FrankfurterRate(day("2026-10-24"), "JPY", BigDecimal("23.561")),
            FrankfurterRate(day("2026-10-24"), "USD", BigDecimal("0.15001"))), rates)
    }

    @Test fun single_day_keeps_earlier_and_mixed_source_dates() = runTest {
        val transport = FakeTransport { ok("[${row("2026-10-23", "TND", "0.4012")},${row("2026-10-24", "USD", "0.15001")}]") }
        val rates = FrankfurterClient(transport).rates("CNY", listOf("USD", "TND"), day("2026-10-24"))
        assertEquals(mapOf("TND" to day("2026-10-23"), "USD" to day("2026-10-24")), rates.associate { it.quote to it.date })
    }

    @Test fun missing_quotes_are_simply_absent() = runTest {
        val transport = FakeTransport { ok("[${row("2026-10-24", "USD", "0.15")}]") }
        val rates = FrankfurterClient(transport).rates("CNY", listOf("USD", "TND", "NPR"), day("2026-10-24"))
        assertEquals(listOf("USD"), rates.map { it.quote })
        assertEquals(emptyList<FrankfurterRate>(),
            FrankfurterClient(FakeTransport { ok("[]") }).rates("CNY", listOf("USD"), day("1980-01-01")))
    }

    @Test fun series_keeps_gaps_and_splits_long_spans_by_year() = runTest {
        val transport = FakeTransport { url ->
            if (url.contains("from=2026-10-20")) ok("[${row("2026-10-20", "USD", "0.14981")},${row("2026-10-23", "USD", "0.15")}]")
            else ok("[]")
        }
        val client = FrankfurterClient(transport)
        val rates = client.series("CNY", listOf("USD"), day("2026-10-20"), day("2026-10-25"))
        assertEquals(listOf(day("2026-10-20"), day("2026-10-23")), rates.map { it.date })
        assertEquals(listOf("https://api.frankfurter.dev/v2/rates?base=CNY&quotes=USD&from=2026-10-20&to=2026-10-25"), transport.urls)

        transport.urls.clear()
        client.series("CNY", listOf("USD"), day("2023-03-01"), day("2026-10-27"))
        assertEquals(listOf("from=2023-03-01&to=2024-02-29", "from=2024-03-01&to=2025-02-28",
            "from=2025-03-01&to=2026-02-28", "from=2026-03-01&to=2026-10-27"),
            transport.urls.map { it.substringAfter("quotes=USD&") })

        transport.urls.clear()
        client.series("CNY", listOf("USD"), day("2024-01-01"), day("2024-12-31"))
        assertEquals(1, transport.urls.size)
    }

    @Test fun rate_keeps_the_original_digits() = runTest {
        val transport = FakeTransport {
            ok("[${row("2026-10-24", "USD", "0.123456789012345678901234567")},${row("2026-10-24", "JPY", "2.3561E1")}]")
        }
        val rates = FrankfurterClient(transport).rates("CNY", listOf("USD", "JPY"), day("2026-10-24")).associateBy { it.quote }
        assertEquals("0.123456789012345678901234567", rates.getValue("USD").rate.toPlainString())
        assertEquals(0, BigDecimal("23.561").compareTo(rates.getValue("JPY").rate))
    }

    @Test fun inverse_rates_use_decimal128_and_round_once_to_twelve_places() {
        assertEquals(BigDecimal.ONE.divide(BigDecimal("0.15001"), MathContext.DECIMAL128), inverseRate(BigDecimal("0.15001")))
        assertEquals(34, inverseRate(BigDecimal("0.15001")).precision())
        assertEquals(BigDecimal("6.666222251850"), currentRateValue(BigDecimal("0.15001")))
        assertEquals(BigDecimal("0.042443020245"), currentRateValue(BigDecimal("23.561")))
        assertEquals(BigDecimal("2.000000000000"), currentRateValue(BigDecimal("0.5")))
        // 1 / 4E11 = 2.5E-12 is an exact half and goes to the even neighbour.
        assertEquals(BigDecimal("0.000000000002"), currentRateValue(BigDecimal("4E11")))
        assertEquals(BigDecimal("0.000000000001"), currentRateValue(BigDecimal("1E12")))
    }

    @Test fun http_failures_map_to_typed_errors() = runTest {
        for ((status, expected) in listOf(422 to FrankfurterError.CLIENT, 404 to FrankfurterError.CLIENT,
            400 to FrankfurterError.CLIENT, 429 to FrankfurterError.RATE_LIMITED, 500 to FrankfurterError.SERVER,
            503 to FrankfurterError.SERVER, 302 to FrankfurterError.SERVER)) {
            val client = FrankfurterClient(FakeTransport { FrankfurterResponse(status, """{"status":$status}""".toByteArray()) })
            expectError(expected, status) { client.rates("CNY", listOf("USD"), day("2026-10-24")) }
        }
    }

    @Test fun connection_failures_map_to_typed_errors() = runTest {
        for ((thrown, expected) in listOf(SocketTimeoutException() to FrankfurterError.TIMEOUT,
            UnknownHostException() to FrankfurterError.DNS, IOException() to FrankfurterError.NETWORK)) {
            val client = FrankfurterClient(FakeTransport { throw thrown })
            expectError(expected) { client.rates("CNY", listOf("USD"), day("2026-10-24")) }
        }
    }

    @Test fun malformed_or_unexpected_bodies_are_parse_errors() = runTest {
        val bodies = listOf(
            "not json",
            "{\"date\":\"2026-10-24\"}",
            "[1]",
            "[${row("2026-10-24", "USD", "0.15", base = "EUR")}]",
            "[${row("2026-10-24", "EUR", "0.13")}]",
            "[${row("2026-10-25", "USD", "0.15")}]",
            "[${row("2026-13-01", "USD", "0.15")}]",
            "[${row("2026-10-24", "USD", "0")}]",
            "[${row("2026-10-24", "USD", "-0.15")}]",
            "[${row("2026-10-24", "USD", "\"0.15\"")}]",
            "[${row("2026-10-24", "USD", "null")}]",
            "[${row("2026-10-24", "USD", "1E13")}]",
            "[${row("2026-10-24", "USD", "0.15")},${row("2026-10-24", "USD", "0.16")}]",
            "[{\"date\":\"2026-10-24\",\"base\":\"CNY\",\"quote\":\"USD\"}]",
        )
        for (body in bodies) {
            val client = FrankfurterClient(FakeTransport { ok(body) })
            expectError(FrankfurterError.PARSE) { client.rates("CNY", listOf("USD"), day("2026-10-24")) }
        }
        val invalidUtf8 = FrankfurterClient(FakeTransport { FrankfurterResponse(200, byteArrayOf(0x5b, 0xff.toByte(), 0x5d)) })
        expectError(FrankfurterError.PARSE) { invalidUtf8.rates("CNY", listOf("USD"), day("2026-10-24")) }
        val outOfRange = FrankfurterClient(FakeTransport { ok("[${row("2026-10-19", "USD", "0.15")}]") })
        expectError(FrankfurterError.PARSE) { outOfRange.series("CNY", listOf("USD"), day("2026-10-20"), day("2026-10-25")) }
    }

    @Test fun requests_outside_the_supported_currencies_are_rejected_before_sending() = runTest {
        val transport = FakeTransport { ok("[]") }
        val client = FrankfurterClient(transport)
        for (call in listOf<suspend () -> Unit>(
            { client.rates("ABC", listOf("USD"), day("2026-10-24")) },
            { client.rates("CNY", listOf("CNY"), day("2026-10-24")) },
            { client.rates("CNY", emptyList(), day("2026-10-24")) },
            { client.rates("CNY", listOf("XYZ"), day("2026-10-24")) },
            { client.series("CNY", listOf("USD"), day("2026-10-25"), day("2026-10-24")) },
        )) {
            try { call(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(transport.urls.isEmpty())
    }
}
