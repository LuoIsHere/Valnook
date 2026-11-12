package dev.valnook.feature.bills

import java.math.BigDecimal
import java.math.RoundingMode

/** A key on the bill keypad. */
enum class BillKey(val symbol: String) {
    D0("0"), D1("1"), D2("2"), D3("3"), D4("4"), D5("5"), D6("6"), D7("7"), D8("8"), D9("9"), D00("00"),
    POINT("."), PLUS("+"), MINUS("−"), TIMES("×"), DIVIDE("÷"), BACKSPACE("⌫"), CLEAR("C");

    val isOperator: Boolean get() = this == PLUS || this == MINUS || this == TIMES || this == DIVIDE
}

/**
 * What the keypad has typed: non-negative numbers joined by + − × ÷, worked out with × and ÷ before + and −, the
 * way the line under the amount reads. Each number keeps to the currency's decimal places.
 */
data class BillExpression(val text: String = "") {
    val isEmpty: Boolean get() = text.isEmpty()

    /** Whether there is still something to work out, so the confirm key reads "=". */
    val hasOperator: Boolean get() = text.trimEnd { it in OPERATORS }.any { it in OPERATORS }

    fun press(key: BillKey, fractionDigits: Int): BillExpression {
        val number = text.takeLastWhile { it !in OPERATORS }
        val next = when {
            key == BillKey.CLEAR -> ""
            key == BillKey.BACKSPACE -> text.dropLast(1)
            key.isOperator -> when {
                text.isEmpty() -> text
                text.last() in OPERATORS || text.last() == '.' -> text.dropLast(1) + key.symbol
                else -> text + key.symbol
            }
            key == BillKey.POINT -> when {
                fractionDigits == 0 || '.' in number -> text
                number.isEmpty() -> text + "0."
                else -> "$text."
            }
            else -> key.symbol.fold(text) { result, digit -> digit(result, digit, fractionDigits) }
        }
        return if (next.length > MAX_LENGTH) this else BillExpression(next)
    }

    private fun digit(text: String, digit: Char, fractionDigits: Int): String {
        val number = text.takeLastWhile { it !in OPERATORS }
        val point = number.indexOf('.')
        if (point >= 0 && number.length - point - 1 >= fractionDigits) return text
        // A leading zero gives way to the next digit, so "0" then "5" reads "5" rather than "05".
        if (number == "0") return text.dropLast(1) + digit
        return text + digit
    }

    /** The value rounded half up to [fractionDigits]; a trailing operator is ignored. Null when empty or dividing by 0. */
    fun value(fractionDigits: Int): BigDecimal? {
        val body = text.trimEnd { it in OPERATORS || it == '.' }
        if (body.isEmpty()) return null
        val numbers = mutableListOf<BigDecimal>()
        val operators = mutableListOf<Char>()
        var start = 0
        body.forEachIndexed { index, char ->
            if (char in OPERATORS) {
                numbers += parse(body.substring(start, index)) ?: return null
                operators += char
                start = index + 1
            }
        }
        numbers += parse(body.substring(start)) ?: return null
        // × and ÷ first, left to right, then + and −.
        val terms = mutableListOf(numbers.first())
        val signs = mutableListOf<Char>()
        operators.forEachIndexed { index, op ->
            val right = numbers[index + 1]
            when (op) {
                '×' -> terms[terms.lastIndex] = terms.last().multiply(right)
                '÷' -> {
                    if (right.signum() == 0) return null
                    terms[terms.lastIndex] = terms.last().divide(right, 12, RoundingMode.HALF_UP)
                }
                else -> { signs += op; terms += right }
            }
        }
        var total = terms.first()
        signs.forEachIndexed { index, op -> total = if (op == '+') total.add(terms[index + 1]) else total.subtract(terms[index + 1]) }
        return total.setScale(fractionDigits, RoundingMode.HALF_UP)
    }

    /** The minor units of [value], or null when there is no value or it does not fit. */
    fun minor(fractionDigits: Int): Long? = value(fractionDigits)?.let {
        try { it.movePointRight(fractionDigits).longValueExact() } catch (_: ArithmeticException) { null }
    }

    /** The worked-out value as the only number, for the confirm key's "=". */
    fun settled(fractionDigits: Int): BillExpression =
        value(fractionDigits)?.let { BillExpression(it.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()) } ?: this

    private fun parse(value: String): BigDecimal? = if (value.isEmpty() || value == ".") null else value.toBigDecimalOrNull()

    companion object {
        private val OPERATORS = setOf('+', '−', '×', '÷')
        private const val MAX_LENGTH = 40

        /** The expression holding [minor] as a plain number, as an edit starts. */
        fun of(minor: Long, fractionDigits: Int): BillExpression =
            BillExpression(BigDecimal.valueOf(minor, fractionDigits).stripTrailingZeros().toPlainString())

        /** The expression holding [value] as a plain number. */
        fun of(value: BigDecimal): BillExpression = BillExpression(value.stripTrailingZeros().toPlainString())
    }
}
