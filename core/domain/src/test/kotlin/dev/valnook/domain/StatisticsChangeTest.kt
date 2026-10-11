package dev.valnook.domain

import dev.valnook.domain.model.StatisticsPoint
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class StatisticsChangeTest {
    private fun point(current:String?,baseline:String?,reliable:Boolean=true)=StatisticsPoint(LocalDate.of(2026,10,11),
        current?.toBigDecimal(),reliable,comparisonValue=baseline?.toBigDecimal())
    @Test fun amount_and_percentage_use_the_same_baseline() {
        assertEquals(BigDecimal("25"),point("125","100").change)
        assertEquals(BigDecimal("25.00"),point("125","100").changePercent)
        assertEquals(BigDecimal("-20.00"),point("80","100").changePercent)
        assertEquals(BigDecimal("0.00"),point("100","100").changePercent)
    }
    @Test fun zero_negative_or_missing_baselines_never_invent_percentages() {
        assertNull(point("100","0").changePercent)
        assertNull(point("100","-50").changePercent)
        assertNull(point("100",null).change)
        assertNull(point(null,"100").change)
        assertNull(point("100","90",false).change)
        assertNull(point("100","90").copy(future=true).change)
    }
}
