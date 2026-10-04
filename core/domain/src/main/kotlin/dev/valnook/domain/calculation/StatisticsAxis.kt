package dev.valnook.domain.calculation

import dev.valnook.domain.model.AxisScale
import java.math.BigDecimal
import java.math.RoundingMode

object StatisticsAxis {
    private val choices = listOf("1", "2", "2.5", "5", "10").map(::BigDecimal)
    private val compactUnits = listOf(
        BigDecimal("1000000000000"),
        BigDecimal("1000000000"),
        BigDecimal("1000000"),
        BigDecimal("1000"),
    )

    fun scale(values: List<BigDecimal>, previous: AxisScale? = null): AxisScale? {
        if (values.isEmpty()) return null
        val rawMin = values.minOrNull()!!
        val rawMax = values.maxOrNull()!!
        if (previous != null && rawMin >= previous.minimum && rawMax <= previous.maximum) return previous
        val magnitude = rawMin.abs().max(rawMax.abs()).max(BigDecimal.ONE)
        val spread = (rawMax - rawMin).abs()
        val padding = if (spread.signum() == 0) constantSeriesPadding(magnitude)
            else spread.multiply(BigDecimal("0.1"))
        val desiredMin = rawMin - padding
        val desiredMax = rawMax + padding
        val padded = desiredMax - desiredMin
        val target = padded.divide(BigDecimal(5), 18, RoundingMode.HALF_UP)
        val exponent = target.precision() - target.scale() - 1
        val candidates = (-2..2).flatMap { shift ->
            val power = BigDecimal.ONE.scaleByPowerOfTen(exponent + shift)
            choices.map { it.multiply(power) }
        }.distinct().sorted()
        val step = candidates.minByOrNull { candidate ->
            val low = desiredMin.divide(candidate, 0, RoundingMode.FLOOR).multiply(candidate)
            val high = desiredMax.divide(candidate, 0, RoundingMode.CEILING).multiply(candidate)
            val intervals = high.subtract(low).divide(candidate, 0, RoundingMode.HALF_UP).toInt()
            when {
                intervals in 4..6 -> kotlin.math.abs(intervals - 5)
                intervals < 4 -> 20 + (4 - intervals)
                else -> 20 + (intervals - 6)
            }
        } ?: target
        var minimum = desiredMin.divide(step, 0, RoundingMode.FLOOR).multiply(step)
        var maximum = desiredMax.divide(step, 0, RoundingMode.CEILING).multiply(step)
        if (minimum == maximum) {
            minimum -= step
            maximum += step
        }
        val ticks = buildList {
            var value = minimum
            while (value <= maximum && size < 8) {
                add(value.stripTrailingZeros())
                value += step
            }
        }
        return AxisScale(minimum.stripTrailingZeros(), maximum.stripTrailingZeros(), ticks)
    }

    private fun constantSeriesPadding(magnitude: BigDecimal): BigDecimal {
        val compactUnit = compactUnits.firstOrNull { magnitude >= it }
        return compactUnit?.divide(BigDecimal.TEN)
            ?: magnitude.multiply(BigDecimal("0.01")).max(BigDecimal("0.01"))
    }
}
