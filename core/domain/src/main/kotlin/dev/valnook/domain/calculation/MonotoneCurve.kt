package dev.valnook.domain.calculation

import kotlin.math.hypot

data class CurvePoint(val x: Float, val y: Float)
data class CubicSegment(val start: CurvePoint, val control1: CurvePoint, val control2: CurvePoint, val end: CurvePoint)

/** Fritsch-Carlson tangents keep each cubic segment inside the neighbouring data range. */
object MonotoneCurve {
    fun segments(points: List<CurvePoint>): List<CubicSegment> {
        if (points.size < 2) return emptyList()
        if (points.size == 2) {
            val first = points[0]
            val last = points[1]
            val third = (last.x - first.x) / 3f
            val thirdY = (last.y - first.y) / 3f
            return listOf(CubicSegment(first, CurvePoint(first.x + third, first.y + thirdY),
                CurvePoint(last.x - third, first.y + thirdY * 2f), last))
        }
        val delta = FloatArray(points.lastIndex) { index ->
            (points[index + 1].y - points[index].y) / (points[index + 1].x - points[index].x)
        }
        val tangent = FloatArray(points.size)
        tangent[0] = delta[0]
        tangent[tangent.lastIndex] = delta[delta.lastIndex]
        for (index in 1 until tangent.lastIndex) tangent[index] = (delta[index - 1] + delta[index]) / 2f
        delta.indices.forEach { index ->
            if (delta[index] == 0f) {
                tangent[index] = 0f
                tangent[index + 1] = 0f
            } else {
                val a = tangent[index] / delta[index]
                val b = tangent[index + 1] / delta[index]
                val length = hypot(a, b)
                if (length > 3f) {
                    val scale = 3f / length
                    tangent[index] = scale * a * delta[index]
                    tangent[index + 1] = scale * b * delta[index]
                }
            }
        }
        return delta.indices.map { index ->
            val start = points[index]
            val end = points[index + 1]
            val third = (end.x - start.x) / 3f
            val low = minOf(start.y, end.y)
            val high = maxOf(start.y, end.y)
            CubicSegment(start, CurvePoint(start.x + third,
                (start.y + tangent[index] * third).coerceIn(low, high)),
                CurvePoint(end.x - third,
                    (end.y - tangent[index + 1] * third).coerceIn(low, high)), end)
        }
    }
}
