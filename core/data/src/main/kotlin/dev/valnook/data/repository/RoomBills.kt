package dev.valnook.data.repository

import dev.valnook.data.database.BillAccountRow
import dev.valnook.data.database.BillCategoryEntity
import dev.valnook.data.database.BillEntryEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import java.time.ZoneId

class RoomBills(private val db: ValnookDatabase) : BillRepository {
    private val dao = db.bills()

    override fun observeCategories(): Flow<List<BillCategory>> =
        dao.observeCategories().map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeBill(billId: Long): Flow<Bill?> = dao.observeBill(billId).map { it?.toModel() }.distinctUntilChanged()

    override fun observeMonth(month: YearMonth, zone: ZoneId): Flow<List<Bill>> {
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeRange(start, end).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()
    }

    override fun observeSearch(query: String, limit: Int): Flow<List<Bill>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.observeSearch(escaped, limit).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()
    }

    override fun observeFirstBillAt(): Flow<Long?> = dao.observeFirstBillAt().distinctUntilChanged()

    override fun observeAccountChoices(): Flow<List<BillAccountChoice>> =
        dao.observeAccountRows().map(::choices).distinctUntilChanged()

    override fun observeRecentNotes(categoryId: Long, limit: Int): Flow<List<String>> =
        dao.observeRecentNotes(categoryId, limit).distinctUntilChanged()

    override suspend fun categoryUsage(categoryId: Long): Int = dao.categoryUsage(categoryId)

    override suspend fun lastUsedCashAccountId(): Long? = dao.lastUsedCashAccountId()

    override suspend fun storedRatesOn(base: String, day: java.time.LocalDate): Map<String, java.math.BigDecimal> =
        db.fx().historyOn(base, day.toEpochDay()).associate { it.quote_currency to it.rate.toBigDecimal() }
}

/** A credit sub-account's limit is its own or, when shared, the source's; every member's debt uses it up. */
internal fun choices(rows: List<BillAccountRow>): List<BillAccountChoice> = rows.map { row ->
    val available = if (!row.is_credit) null else {
        val root = rows.firstOrNull { it.id == (row.limit_source_account_id ?: row.id) }
        val limit = root?.credit_limit_minor
        if (root == null || limit == null) null else {
            val members = rows.filter { it.id == root.id || it.limit_source_account_id == root.id }
            members.fold(limit) { left, member -> left - maxOf(0L, -member.balance_minor) }
        }
    }
    BillAccountChoice(row.id, row.savings_account_id, row.account_name, row.name, Currency.of(row.currency_code),
        row.balance_minor, row.is_credit, available, row.is_favorite, row.favorite_order, row.show_on_accounts_page,
        row.account_order, row.display_order,
        if (row.icon_type == "SYMBOL") row.icon_value else "account_balance",
        if (row.icon_type == "IMAGE") row.icon_value else null)
}

internal fun BillCategoryEntity.toModel() = BillCategory(id, BillCategoryKind.valueOf(kind), parent_id, builtin_key,
    custom_name, icon_key, color_key, sort_order, hidden, last_cash_account_id, revision)

internal fun BillEntryEntity.toModel() = Bill(
    id = id, type = BillType.valueOf(type), occurredAtMs = occurred_at_ms, amountMinor = amount_minor,
    currency = Currency.of(currency_code), discountMinor = discount_minor, cashAccountId = cash_account_id,
    accountCurrency = Currency.of(account_currency_code), accountAmountMinor = account_amount_minor,
    rate = rate.toBigDecimal(), rateSource = BillRateSource.valueOf(rate_source),
    transferKind = transfer_kind?.let(TransferKind::valueOf), toCashAccountId = to_cash_account_id,
    toCurrency = to_currency_code?.let(Currency::of), toAmountMinor = to_amount_minor, feeMinor = fee_minor,
    counterparty = counterparty, categoryId = category_id, subCategoryId = sub_category_id,
    countInStats = count_in_stats, reimbursable = reimbursable, reimburseState = ReimburseState.valueOf(reimburse_state),
    parentId = parent_id, note = note, revision = revision, createdAtMs = created_at_ms, updatedAtMs = updated_at_ms)
