package dev.valnook.data.fx

import dev.valnook.domain.model.Currency
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The only place the rate source is configured. A self-hosted instance only needs this line changed. */
internal const val FRANKFURTER_BASE_URL = "https://api.frankfurter.dev/v2/"

/** Decimal places stored in `fx_rates`, the same limit the rate editor enforces. */
internal const val FX_RATE_DECIMALS = 12

/** One published row: 1 base = [rate] [quote], as the source had it on [date]. */
internal data class FrankfurterRate(val date: LocalDate, val quote: String, val rate: BigDecimal)

internal enum class FrankfurterError { CLIENT, RATE_LIMITED, SERVER, TIMEOUT, DNS, NETWORK, PARSE }

internal class FrankfurterException(val error: FrankfurterError, val status: Int? = null, cause: Throwable? = null) :
    IllegalStateException("Frankfurter request failed: $error", cause)

internal class FrankfurterResponse(val status: Int, val body: ByteArray) {
    override fun toString() = "FrankfurterResponse(status=$status)"
}

/** Replaced in tests; there is no runtime switch for the address. */
internal fun interface FrankfurterTransport {
    suspend fun get(url: String): FrankfurterResponse
}

internal class UrlConnectionFrankfurterTransport : FrankfurterTransport {
    override suspend fun get(url: String) = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Valnook")
            val status = connection.responseCode
            val memory = ByteArrayOutputStream()
            // Error bodies are not needed: the status alone decides the error type.
            if (status in 200..299) connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (memory.size() + size > MAX_BODY_BYTES) throw FrankfurterException(FrankfurterError.PARSE, status)
                    memory.write(buffer, 0, size)
                }
            }
            FrankfurterResponse(status, memory.toByteArray())
        } finally { connection.disconnect() }
    }

    private companion object {
        // One year of all supported quotes is about 1.1 MB.
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

internal class FrankfurterClient(private val transport: FrankfurterTransport) {
    /** For each quote, the source's value on [date] or the nearest earlier day it has. */
    suspend fun rates(base: String, quotes: Collection<String>, date: LocalDate): List<FrankfurterRate> {
        val quoteSet = checkedQuotes(base, quotes)
        val body = fetch("rates?base=$base&quotes=${quoteSet.joinToString(",")}&date=$date")
        return parse(body, base, quoteSet, LocalDate.MIN, date)
    }

    /**
     * The days the source published from [from] to [to]; spans longer than 366 days go in yearly parts. For a quote
     * with nothing published on a part's first day the source also sends its last value before that day; only the
     * newest such row per quote is kept, as the value carried into the part.
     */
    suspend fun series(base: String, quotes: Collection<String>, from: LocalDate, to: LocalDate): List<FrankfurterRate> {
        val quoteSet = checkedQuotes(base, quotes)
        require(!from.isAfter(to))
        val result = mutableListOf<FrankfurterRate>()
        var start = from
        while (!start.isAfter(to)) {
            val end = minOf(start.plusYears(1).minusDays(1), to)
            val body = fetch("rates?base=$base&quotes=${quoteSet.joinToString(",")}&from=$start&to=$end")
            val (carried, inRange) = parse(body, base, quoteSet, LocalDate.MIN, end).partition { it.date.isBefore(start) }
            result += carried.groupBy { it.quote }.map { (_, rows) -> rows.maxBy { it.date } } + inRange
            start = end.plusDays(1)
        }
        return result
    }

    private fun checkedQuotes(base: String, quotes: Collection<String>): Set<String> {
        val supported = Currency.supported.mapTo(hashSetOf()) { it.code }
        require(base in supported)
        val quoteSet = quotes.toSortedSet()
        require(quoteSet.isNotEmpty() && base !in quoteSet && supported.containsAll(quoteSet))
        return quoteSet
    }

    private suspend fun fetch(path: String): ByteArray {
        val response = try {
            transport.get(FRANKFURTER_BASE_URL + path)
        } catch (error: FrankfurterException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw FrankfurterException(FrankfurterError.TIMEOUT, cause = error)
        } catch (error: UnknownHostException) {
            throw FrankfurterException(FrankfurterError.DNS, cause = error)
        } catch (error: IOException) {
            throw FrankfurterException(FrankfurterError.NETWORK, cause = error)
        }
        val status = response.status
        return when {
            status in 200..299 -> response.body
            status == 429 -> throw FrankfurterException(FrankfurterError.RATE_LIMITED, status)
            status in 400..499 -> throw FrankfurterException(FrankfurterError.CLIENT, status)
            // Redirects are not followed, so a 3xx is treated like a server fault.
            else -> throw FrankfurterException(FrankfurterError.SERVER, status)
        }
    }

    private fun parse(body: ByteArray, base: String, quotes: Set<String>,
        earliest: LocalDate, latest: LocalDate): List<FrankfurterRate> = try {
        val rows = Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonArray
            ?: parseError()
        val seen = hashSetOf<Pair<LocalDate, String>>()
        rows.map { element ->
            val row = element as? JsonObject ?: parseError()
            if (text(row, "base") != base) parseError()
            val quote = text(row, "quote")
            if (quote !in quotes) parseError()
            val date = LocalDate.parse(text(row, "date"))
            if (date.isBefore(earliest) || date.isAfter(latest)) parseError()
            if (!seen.add(date to quote)) parseError()
            FrankfurterRate(date, quote, rate(row))
        }
    } catch (error: FrankfurterException) {
        throw error
    } catch (error: SerializationException) {
        throw FrankfurterException(FrankfurterError.PARSE, cause = error)
    } catch (error: CharacterCodingException) {
        throw FrankfurterException(FrankfurterError.PARSE, cause = error)
    } catch (error: DateTimeParseException) {
        throw FrankfurterException(FrankfurterError.PARSE, cause = error)
    } catch (error: NumberFormatException) {
        throw FrankfurterException(FrankfurterError.PARSE, cause = error)
    }

    private fun text(row: JsonObject, key: String): String {
        val value = row[key] as? JsonPrimitive ?: parseError()
        if (!value.isString) parseError()
        return value.content
    }

    /** Built from the number's original text, never through Double. */
    private fun rate(row: JsonObject): BigDecimal {
        val value = row["rate"] as? JsonPrimitive ?: parseError()
        if (value.isString || value.content.length > 40) parseError()
        val rate = BigDecimal(value.content)
        if (rate < MIN_RATE || rate > MAX_RATE) parseError()
        return rate
    }

    private fun parseError(): Nothing = throw FrankfurterException(FrankfurterError.PARSE)

    private companion object {
        // Outside these bounds the inverse no longer fits the 12 decimal places of fx_rates.
        val MIN_RATE = BigDecimal("1E-12")
        val MAX_RATE = BigDecimal("1E12")
    }
}

/** App direction (1 quote = x base) at full precision, used for statistics. */
internal fun inverseRate(raw: BigDecimal): BigDecimal = BigDecimal.ONE.divide(raw, MathContext.DECIMAL128)

/** App direction rounded once to the places `fx_rates` keeps. */
internal fun currentRateValue(raw: BigDecimal): BigDecimal =
    BigDecimal.ONE.divide(raw, FX_RATE_DECIMALS, RoundingMode.HALF_EVEN)
