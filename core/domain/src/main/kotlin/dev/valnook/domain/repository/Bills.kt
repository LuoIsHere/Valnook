package dev.valnook.domain.repository

import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.Currency
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth
import java.time.ZoneId

sealed interface BillCommand : FinancialCommand

/** A new bill when [billId] is null, otherwise an edit of the bill last read at [expectedRevision]. */
data class SaveBill(override val operation_id: String, val billId: Long?, val expectedRevision: Long?,
    val draft: BillDraft) : BillCommand

/** Removes the bill and moves its sub-accounts back by what it moved them. */
data class DeleteBill(override val operation_id: String, val billId: Long, val expectedRevision: Long) : BillCommand

/**
 * A new category when [categoryId] is null; [parentId] makes it a subcategory of a top-level category of the same
 * [kind]. For a preset, a null [name] keeps the name following the language.
 */
data class SaveBillCategory(override val operation_id: String, val categoryId: Long?, val expectedRevision: Long?,
    val kind: BillCategoryKind, val parentId: Long?, val name: String?, val iconKey: String,
    val colorKey: String) : BillCommand

data class SetBillCategoryHidden(override val operation_id: String, val categoryId: Long, val expectedRevision: Long,
    val hidden: Boolean) : BillCommand

/** [orderedIds] are every category under [parentId] (top level when null) of [kind], in their new order. */
data class ReorderBillCategories(override val operation_id: String, val kind: BillCategoryKind, val parentId: Long?,
    val orderedIds: List<Long>) : BillCommand

/**
 * Deletes a category, preset or not, except the two that always stay (BillCategoryPresets.PROTECTED). A top-level
 * category must have no subcategories left, hidden ones included. Its bills either move to [moveToCategoryId] (of the
 * same kind, top-level or not) or, with [deleteBills], are deleted with their refunds and reimbursements; one of the
 * two is required when it has any.
 *
 * Deleted this way, bills leave balances as they are: their cash entries stay as CashSource.BILL_REMOVED, noted with
 * the bill's note or, without one, [billTitle] (the category's name as the user reads it, e.g. "Food-Lunch").
 * Only deleting a bill on its own moves its accounts back.
 */
data class DeleteBillCategory(override val operation_id: String, val categoryId: Long, val expectedRevision: Long,
    val moveToCategoryId: Long?, val deleteBills: Boolean = false, val billTitle: String = "") : BillCommand

/** The sub-accounts pinned at the top of the account picker, in order; every other one is unpinned. */
data class SaveBillFavorites(override val operation_id: String, val orderedCashAccountIds: List<Long>) : BillCommand

/** A sub-account as the bill account picker shows it. */
data class BillAccountChoice(
    val cashAccountId: Long,
    val accountId: Long,
    val accountName: String,
    val cashAccountName: String,
    val currency: Currency,
    val balanceMinor: Long,
    /** Credit sub-accounts show what is left to spend; null when the limit is unknown. */
    val isCredit: Boolean,
    val availableCreditMinor: Long?,
    val favorite: Boolean,
    val favoriteOrder: Long,
    val shownOnAccountsPage: Boolean,
    /** Account and sub-account display order, for the "all accounts" list. */
    val accountOrder: Long,
    val cashOrder: Long,
    val iconSymbol: String,
    val iconImageKey: String?,
)

interface BillRepository {
    fun observeCategories(): Flow<List<BillCategory>>
    fun observeBill(billId: Long): Flow<Bill?>
    /** Every bill dated in [month] in [zone], newest first. */
    fun observeMonth(month: YearMonth, zone: ZoneId): Flow<List<Bill>>
    /** Bills whose note contains [query], newest first. */
    fun observeSearch(query: String, limit: Int = 200): Flow<List<Bill>>
    /** The earliest month that has a bill, if any. */
    fun observeFirstBillAt(): Flow<Long?>
    fun observeAccountChoices(): Flow<List<BillAccountChoice>>
    /**
     * Notes used before on exactly this category, most used first, then most recent: with [subCategoryId] that
     * subcategory's, otherwise those on [categoryId] without a subcategory. Each category keeps its own.
     */
    fun observeRecentNotes(categoryId: Long, subCategoryId: Long?, limit: Int = 6): Flow<List<String>>
    /** How many bills (refunds and reimbursements aside) still use the category or its subcategories. */
    suspend fun categoryUsage(categoryId: Long): Int
    /** The automatic rates stored for [day] under [base]: 1 base = value of each quote currency. */
    suspend fun storedRatesOn(base: String, day: java.time.LocalDate): Map<String, java.math.BigDecimal> = emptyMap()
    /** The sub-account of the most recently added expense or income, for a new bill's account. */
    suspend fun lastUsedCashAccountId(): Long? = null
}
