package dev.valnook.domain.money

import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Integers represent currency minor units or 10^-8 units, never binary floating point. */
object DecimalRules {
    private val decimal_pattern = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val signed_decimal_pattern = Regex("-?[0-9]+(?:\\.[0-9]+)?")
    fun parse_units(input: String, scale: Int, positive: Boolean = false): Long {
        val text = input.trim()
        if (text.length > 64 || !decimal_pattern.matches(text)) throw DomainException(ErrorCode.FORMAT)
        val decimal = BigDecimal(text)
        if (decimal.stripTrailingZeros().scale() > scale) throw DomainException(ErrorCode.PRECISION)
        val value = exact_long(decimal.movePointRight(scale))
        check_nonnegative(value, positive)
        return value
    }
    fun parse_minor(input: String, currency: Currency, positive: Boolean = false) =
        parse_units(input, currency.fraction_digits, positive)
    fun parse_signed_units(input: String, scale: Int): Long {
        val text = input.trim()
        if (text.length > 65 || !signed_decimal_pattern.matches(text)) throw DomainException(ErrorCode.FORMAT)
        val decimal = BigDecimal(text)
        if (decimal.stripTrailingZeros().scale() > scale) throw DomainException(ErrorCode.PRECISION)
        return exact_long(decimal.movePointRight(scale))
    }
    fun parse_signed_minor(input: String, currency: Currency) =
        parse_signed_units(input, currency.fraction_digits)
    fun parse_e8(input: String, positive: Boolean = false) = parse_units(input, 8, positive)
    fun check_nonnegative(value: Long, positive: Boolean = false) {
        if (value < 0 || (positive && value == 0L)) throw DomainException(ErrorCode.POSITIVE)
    }
    fun exact_long(value: BigDecimal): Long = try { value.longValueExact() }
        catch (_: ArithmeticException) { throw DomainException(ErrorCode.OVERFLOW) }
    fun add(a: Long, b: Long): Long = try { Math.addExact(a, b) }
        catch (_: ArithmeticException) { throw DomainException(ErrorCode.OVERFLOW) }
    fun replace_contribution(current: Long, previous: Long, replacement: Long): Long =
        exact_long(BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(previous)).add(BigDecimal.valueOf(replacement)))
    fun format_units(value: Long, scale: Int): String = BigDecimal.valueOf(value, scale).toPlainString()
    fun format_display(value: Long, scale: Int): String {
        val parts = format_units(value, scale).split('.')
        val negative = parts.first().startsWith("-")
        val digits = parts.first().removePrefix("-")
        val integer = (if(negative) "-" else "") + digits.reversed().chunked(3).joinToString(" ").reversed()
        return integer + if (parts.size == 2) "." + parts[1] else ""
    }
    fun format_e8(value: Long): String = BigDecimal.valueOf(value, 8).stripTrailingZeros().toPlainString()
    fun amount(quantity_e8: Long, price_e8: Long, currency: Currency, trade: Boolean = false): Long {
        check_nonnegative(quantity_e8, trade); check_nonnegative(price_e8, trade)
        val result = exact_long(BigDecimal.valueOf(quantity_e8, 8)
            .multiply(BigDecimal.valueOf(price_e8, 8))
            .movePointRight(currency.fraction_digits).setScale(0, RoundingMode.HALF_UP))
        if (trade && result == 0L) throw DomainException(ErrorCode.AMOUNT_TOO_SMALL)
        return result
    }
    fun interest(principal_minor: Long, rate_percent_e8: Long, start: Long, end: Long): Long {
        check_nonnegative(principal_minor, true); check_nonnegative(rate_percent_e8)
        val days = days(start, end)
        val value = BigDecimal.valueOf(principal_minor)
            .multiply(BigDecimal.valueOf(rate_percent_e8, 8))
            .multiply(BigDecimal.valueOf(days))
            .divide(BigDecimal("36500"), 0, RoundingMode.HALF_UP)
        val interest = exact_long(value)
        add(principal_minor, interest) // Reject a maturity total that cannot be represented.
        return interest
    }
    fun days(start: Long, end: Long): Long {
        val result = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end))
        if (result <= 0) throw DomainException(ErrorCode.DATE)
        return result
    }
    fun progress(start: Long, end: Long, today: Long): BigDecimal =
        BigDecimal.valueOf((today - start).coerceIn(0, days(start, end)))
            .divide(BigDecimal.valueOf(days(start, end)), 8, RoundingMode.HALF_UP)
    // Material date pickers represent a calendar date at midnight UTC.
    fun picker_to_epoch_day(utc_ms: Long): Long = Instant.ofEpochMilli(utc_ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
    fun epoch_day_to_picker(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
