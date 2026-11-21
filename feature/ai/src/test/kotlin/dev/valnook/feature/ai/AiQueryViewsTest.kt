package dev.valnook.feature.ai

import dev.valnook.domain.ai.AiQueryCall
import dev.valnook.domain.ai.AiQueryRecord
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Where an answer's lookups open in the statistics, and how their ranges read. */
class AiQueryViewsTest {
    private fun category(id: Long, kind: BillCategoryKind, parent: Long?) =
        BillCategory(id, kind, parent, null, "c$id", "icon", "grey", id, false, null, 1)
    private val categories = listOf(category(1, BillCategoryKind.EXPENSE, null), category(11, BillCategoryKind.EXPENSE, 1),
        category(3, BillCategoryKind.INCOME, null)).associateBy { it.id }
    private fun d(text: String) = LocalDate.parse(text)
    private fun ran(call: AiQueryCall, error: Boolean = false) = AiQueryRecord(1, call, 3, error)
    private val november = YearMonth.of(2026, 11)

    @Test fun the_last_query_that_ran_decides_where_statistics_open() {
        fun target(vararg records: AiQueryRecord) = aiStatsTarget(records.toList(), categories)
        val month = AiQueryCall(true, BillType.EXPENSE, d("2026-11-01"), d("2026-11-30"))
        assertEquals(AiStatsTarget.Month(true, november), target(ran(month)))
        // A sub-category opens its parent's page; a list without a type takes it from the category.
        assertEquals(AiStatsTarget.Category(1, true, november),
            target(ran(AiQueryCall(false, null, d("2026-11-03"), d("2026-11-20"), categoryId = 11))))
        assertEquals(AiStatsTarget.Day(d("2026-11-08")), target(ran(AiQueryCall(false, null, d("2026-11-08"), d("2026-11-08")))))
        // Across months, or both kinds at once, the statistics have no page for it.
        assertNull(target(ran(AiQueryCall(true, BillType.INCOME, d("2026-10-01"), d("2026-11-30")))))
        assertNull(target(ran(AiQueryCall(false, null, d("2026-11-01"), d("2026-11-30")))))
        // The latest query with a place in the statistics: a list of both kinds after a month's expense has none.
        assertEquals(AiStatsTarget.Month(true, november),
            target(ran(month), ran(AiQueryCall(false, null, d("2026-11-01"), d("2026-11-30")))))
        // A query that did not run is passed over.
        assertEquals(AiStatsTarget.Month(true, november), target(ran(month), AiQueryRecord(2, null, 0, true)))
        // A look at how assets are spread, dated today, opens the assets rather than that day's bills.
        val distribution = AiQueryCall(true, null, d("2026-11-20"), d("2026-11-20"),
            distribution = dev.valnook.domain.ai.AiDistributionBy.CURRENCY)
        assertEquals(AiStatsTarget.Assets, target(ran(month), ran(distribution)))
        assertNull(target())
    }

    @Test fun ranges_read_as_days_months_or_spans_with_the_year_only_when_needed() {
        val today = d("2026-11-20")
        assertEquals("11 月", aiQueryRange(d("2026-11-01"), d("2026-11-30"), today, english = false))
        assertEquals("11 月 8 日", aiQueryRange(d("2026-11-08"), d("2026-11-08"), today, english = false))
        assertEquals("9 月 – 11 月", aiQueryRange(d("2026-09-01"), d("2026-11-30"), today, english = false))
        assertEquals("11 月 1 日 – 11 月 15 日", aiQueryRange(d("2026-11-01"), d("2026-11-15"), today, english = false))
        assertEquals("2025 年 12 月 – 2026 年 2 月", aiQueryRange(d("2025-12-01"), d("2026-02-28"), today, english = false))
        assertEquals("November", aiQueryRange(d("2026-11-01"), d("2026-11-30"), today, english = true))
        assertEquals("Nov 8, 2025", aiQueryRange(d("2025-11-08"), d("2025-11-08"), today, english = true))
    }
}
