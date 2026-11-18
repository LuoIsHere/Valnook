package dev.valnook.data.repository

import dev.valnook.data.database.BillAccountRow
import dev.valnook.data.database.BillCategoryEntity
import dev.valnook.data.database.BillEntryEntity
import dev.valnook.data.database.BillRow
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillRepository
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RoomBills(private val db: ValnookDatabase) : BillRepository {
    private val dao = db.bills()

    override fun observeCategories(): Flow<List<BillCategory>> =
        dao.observeCategories().map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeBill(billId: Long): Flow<Bill?> = dao.observeBill(billId).map { it?.toModel() }.distinctUntilChanged()

    override fun observeChildren(parentId: Long): Flow<List<Bill>> =
        dao.observeChildren(parentId).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeImages(billId: Long): Flow<List<BillImage>> =
        dao.observeAttachments(billId).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeReimbursable(): Flow<List<Bill>> =
        dao.observeReimbursable().map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()

    override fun observeMonth(month: YearMonth, zone: ZoneId): Flow<List<Bill>> =
        observeRange(month.atDay(1), month.plusMonths(1).atDay(1), zone)

    override fun observeRange(from: LocalDate, until: LocalDate, zone: ZoneId): Flow<List<Bill>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = until.atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.observeRange(start, end).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()
    }

    override fun observeStoredRates(currencies: Set<String>, first: LocalDate, last: LocalDate):
        Flow<Map<String, Map<LocalDate, BigDecimal>>> =
        db.invalidationTracker.createFlow("app_settings", "fx_rate_history").map {
            db.withTransaction {
                val base = db.fx().baseCurrency() ?: return@withTransaction emptyMap()
                val quotes = currencies.filter { it != base }
                if (quotes.isEmpty()) emptyMap()
                // Stored as 1 base = rate quote; inverted once per row to what a bill's amount is multiplied by.
                else db.fx().historyFor(base, quotes, first.toEpochDay(), last.toEpochDay())
                    .groupBy { it.quote_currency }
                    .mapValues { (_, rows) -> rows.associate { row ->
                        LocalDate.ofEpochDay(row.local_day) to BigDecimal.ONE.divide(BigDecimal(row.rate), MathContext.DECIMAL128)
                    } }
            }
        }.flowOn(Dispatchers.IO).distinctUntilChanged()

    override fun observeSearch(query: String, limit: Int): Flow<List<Bill>> = observeSearch(query, null, null, limit)

    override fun observeSearch(query: String, startMs: Long?, endMs: Long?, limit: Int): Flow<List<Bill>> {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.observeSearch(escaped, startMs, endMs, limit).map { rows -> rows.map { it.toModel() } }.distinctUntilChanged()
    }

    override fun observeFirstBillAt(): Flow<Long?> = dao.observeFirstBillAt().distinctUntilChanged()

    override fun observeAccountChoices(): Flow<List<BillAccountChoice>> =
        dao.observeAccountRows().map(::choices).distinctUntilChanged()

    override fun observeRecentNotes(categoryId: Long, subCategoryId: Long?, limit: Int): Flow<List<String>> =
        dao.observeRecentNotes(categoryId, subCategoryId, limit).distinctUntilChanged()

    override suspend fun categoryUsage(categoryId: Long): Int = dao.categoryUsage(categoryId)

    override suspend fun lastUsedCashAccountId(): Long? = dao.lastUsedCashAccountId()

    override suspend fun storedRatesOn(base: String, day: java.time.LocalDate): Map<String, java.math.BigDecimal> =
        db.fx().historyOn(base, day.toEpochDay()).associate { it.quote_currency to it.rate.toBigDecimal() }
}

/**
 * A credit sub-account's limit is its own or, when shared, the source's; every member's debt uses it up and every
 * member's overpayment adds to it, as in CreditLimitCalculator. Without a limit, only an overpayment is available.
 */
internal fun choices(rows: List<BillAccountRow>): List<BillAccountChoice> = rows.map { row ->
    val available = if (!row.is_credit) null else {
        val root = rows.firstOrNull { it.id == (row.limit_source_account_id ?: row.id) }
        val limit = root?.credit_limit_minor
        when {
            root == null -> null
            limit == null -> row.balance_minor.takeIf { it > 0 }
            else -> rows.filter { it.id == root.id || it.limit_source_account_id == root.id }
                .fold(limit) { left, member -> left - maxOf(0L, -member.balance_minor) + maxOf(0L, member.balance_minor) }
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

internal fun BillRow.toModel() = bill.toModel().copy(refundedMinor = refunded_minor, reimbursedMinor = reimbursed_minor,
    parentNote = parent_note, imageCount = image_count.toInt())

internal fun dev.valnook.data.database.BillAttachmentEntity.toModel() =
    BillImage(image_key, byte_size, width.toInt(), height.toInt())

internal fun BillEntryEntity.toModel() = Bill(
    id = id, type = BillType.valueOf(type), occurredAtMs = occurred_at_ms, amountMinor = amount_minor,
    currency = Currency.of(currency_code), discountMinor = discount_minor, cashAccountId = cash_account_id,
    accountCurrency = Currency.of(account_currency_code), accountAmountMinor = account_amount_minor,
    rate = rate.toBigDecimal(), rateSource = BillRateSource.valueOf(rate_source),
    transferKind = transfer_kind?.let(TransferKind::valueOf), toCashAccountId = to_cash_account_id,
    toCurrency = to_currency_code?.let(Currency::of), toAmountMinor = to_amount_minor, feeMinor = fee_minor,
    counterparty = counterparty, categoryId = category_id, subCategoryId = sub_category_id,
    countInStats = count_in_stats, reimbursable = reimbursable, reimburseState = ReimburseState.valueOf(reimburse_state),
    parentId = parent_id, note = note, revision = revision, createdAtMs = created_at_ms, updatedAtMs = updated_at_ms,
    accountDeleted = account_deleted)
