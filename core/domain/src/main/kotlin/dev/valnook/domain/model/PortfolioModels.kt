package dev.valnook.domain.model

import java.math.BigDecimal

enum class InvestmentSection { HOLDING, CLOSED, PENDING }

/** Amounts are in currency units. Missing cost is distinct from a zero profit. */
data class InvestmentProfit(
    val average_cost: BigDecimal?,
    val realized: BigDecimal?,
    val unrealized: BigDecimal?,
    val cost_complete: Boolean,
    val chronology_valid: Boolean
)
