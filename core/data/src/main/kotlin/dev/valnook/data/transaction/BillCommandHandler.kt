package dev.valnook.data.transaction

import dev.valnook.data.database.BillCategoryEntity
import dev.valnook.data.database.BillEntryEntity
import dev.valnook.data.database.CashEntryEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import java.math.BigDecimal

/**
 * Bills and their categories. Runs only inside RoomFinancialCommands' transaction.
 *
 * A bill moves money through its own cash entries (source BILL for its own side, BILL_TRANSFER_IN for a transfer's
 * receiving side). An edit that keeps the same sides updates them in place; one that changes a side replaces them.
 */
internal class BillCommandHandler(
    private val db: ValnookDatabase,
    private val cash: CashWriter,
    private val fault: (TransactionPoint) -> Unit
) {
    private val dao = db.bills()

    suspend fun execute(command: BillCommand, now: Long): OperationResult = when (command) {
        is SaveBill -> save(command, now)
        is DeleteBill -> delete(command, now)
        is SaveBillCategory -> saveCategory(command, now)
        is SetBillCategoryHidden -> setHidden(command, now)
        is ReorderBillCategories -> reorder(command, now)
        is DeleteBillCategory -> deleteCategory(command, now)
        is SaveBillFavorites -> saveFavorites(command)
    }

    /** One cash entry the bill should have. */
    private data class Leg(val kind: CashSource, val cashAccountId: Long, val delta: Long)

    private data class Resolved(val entity: BillEntryEntity, val legs: List<Leg>)

    private suspend fun save(command: SaveBill, now: Long): OperationResult {
        val old = command.billId?.let { active(it, command.expectedRevision ?: throw DomainException(ErrorCode.FORMAT)) }
        if (old == null && (command.billId != null || command.expectedRevision != null)) throw DomainException(ErrorCode.FORMAT)
        val resolved = resolve(command.draft)
        val entity = resolved.entity.copy(
            id = old?.id ?: 0,
            operation_id = old?.operation_id ?: command.operation_id,
            revision = old?.let { R.add(it.revision, 1) } ?: 1,
            created_at_ms = old?.created_at_ms ?: now,
            updated_at_ms = now,
        )
        val id = if (old == null) dao.insertBill(entity) else {
            if (dao.updateBill(entity) != 1) throw DomainException(ErrorCode.STALE_RECORD)
            old.id
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        syncLegs(id, entity.operation_id, command.operation_id, resolved.legs, entity.occurred_at_ms, now,
            if (old == null) "BILL" else "BILL_EDIT")
        // The account a category was last used with (none included) only follows new bills.
        if (old == null && entity.category_id != null) {
            dao.rememberAccount(entity.category_id, entity.cash_account_id)
            entity.sub_category_id?.let { dao.rememberAccount(it, entity.cash_account_id) }
        }
        return OperationResult("BILL", id)
    }

    private suspend fun delete(command: DeleteBill, now: Long): OperationResult {
        val old = active(command.billId, command.expectedRevision)
        remove(old, command.operation_id, now)
        return OperationResult("BILL", old.id)
    }

    /**
     * Deletes [bill] with its refunds and reimbursements, and moves their accounts back by what they moved them.
     * The caller has checked [bill] is the row it read.
     */
    private suspend fun remove(bill: BillEntryEntity, operation: String, now: Long) {
        val rows = listOf(bill) + dao.children(bill.id)
        rows.forEach { row ->
            if (dao.updateBill(row.copy(is_deleted = true, revision = R.add(row.revision, 1), updated_at_ms = now)) != 1) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        val live = rows.flatMap { row -> dao.legs(row.id).filter { !it.is_deleted } }
        live.forEach { leg ->
            if (dao.updateLeg(leg.id, leg.revision, leg.delta_minor, leg.occurred_at_ms, true, now) != 1) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
        cash.applyChanges(operation, live.map { it.cash_account_id to -it.delta_minor }, "BILL_DELETE", now)
    }

    /**
     * Deletes [bill] with its refunds and reimbursements but leaves balances as they are: their cash entries stay,
     * as BILL_REMOVED noted [note]. Only deleting a bill on its own moves its accounts back.
     */
    private suspend fun retire(bill: BillEntryEntity, note: String, now: Long) {
        val rows = listOf(bill) + dao.children(bill.id)
        rows.forEach { row ->
            if (dao.updateBill(row.copy(is_deleted = true, revision = R.add(row.revision, 1), updated_at_ms = now)) != 1) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
            dao.legs(row.id).forEach { leg ->
                // An entry deleted before has no money in the balance; it simply goes.
                if (leg.is_deleted) dao.deleteLeg(leg.id)
                else if (dao.retireLeg(leg.id, leg.revision, note, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
    }

    private suspend fun active(id: Long, expectedRevision: Long): BillEntryEntity {
        val bill = dao.bill(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (bill.is_deleted || bill.revision != expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        // Refunds and reimbursements are edited from their own pages, which arrive in a later build.
        if (bill.type !in EDITABLE) throw DomainException(ErrorCode.SOURCE_RECORD)
        return bill
    }

    private suspend fun syncLegs(billId: Long, billOperation: String, operation: String, wanted: List<Leg>,
        occurred: Long, now: Long, reason: String) {
        val existing = dao.legs(billId)
        val live = existing.filter { !it.is_deleted }
        val sameSides = live.size == wanted.size && wanted.all { leg ->
            live.count { it.source_kind == leg.kind.name && it.cash_account_id == leg.cashAccountId } == 1
        }
        if (sameSides) {
            wanted.forEach { leg ->
                val row = live.single { it.source_kind == leg.kind.name && it.cash_account_id == leg.cashAccountId }
                if (dao.updateLeg(row.id, row.revision, leg.delta, occurred, false, now) != 1) {
                    throw DomainException(ErrorCode.STALE_RECORD)
                }
            }
        } else {
            // A side moved to another sub-account or appeared or went: its old rows give way to new ones (under the
            // bill's own operation, as unique per sub-account), so a side never lingers on the wrong account.
            existing.forEach { dao.deleteLeg(it.id) }
            wanted.forEach { leg -> cash.entry(billOperation, leg.cashAccountId, leg.kind.name, billId, leg.delta, occurred, now) }
        }
        cash.applyChanges(operation, live.map { it.cash_account_id to -it.delta_minor } +
            wanted.map { it.cashAccountId to it.delta }, reason, now)
    }

    /** Checks [draft] and works out the stored row (without identity or revision) and the cash entries it needs. */
    private suspend fun resolve(draft: BillDraft): Resolved {
        val note = BillRules.note(draft.note)
        return when (draft.type) {
            BillType.EXPENSE, BillType.INCOME -> resolveSpending(draft, note)
            BillType.TRANSFER -> resolveTransfer(draft, note)
            BillType.REFUND, BillType.REIMBURSEMENT -> throw DomainException(ErrorCode.FORMAT)
        }
    }

    private suspend fun resolveSpending(draft: BillDraft, note: String): Resolved {
        if (draft.transferKind != null || draft.toCashAccountId != null || draft.toAmountMinor != null ||
            draft.feeMinor != 0L || draft.counterparty.isNotBlank()) throw DomainException(ErrorCode.FORMAT)
        // No account: the bill is kept on its own and moves no balance.
        val account = draft.cashAccountId?.let { account(it) }
        val currency = cash.currency(draft.currencyCode)
        val accountCurrency = account?.let { cash.currency(it.currency_code) } ?: currency
        R.check_nonnegative(draft.amountMinor, positive = true)
        if (draft.discountMinor < 0 || draft.discountMinor >= draft.amountMinor) throw DomainException(ErrorCode.FORMAT)
        val paid = draft.amountMinor - draft.discountMinor
        val (rate, source) = checkedRate(paid, currency, draft.accountAmountMinor, accountCurrency, draft.rate, draft.rateSource)
        val kind = if (draft.type == BillType.EXPENSE) BillCategoryKind.EXPENSE else BillCategoryKind.INCOME
        val category = dao.category(draft.categoryId ?: throw DomainException(ErrorCode.FORMAT))
            ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (category.kind != kind.name || category.parent_id != null) throw DomainException(ErrorCode.FORMAT)
        draft.subCategoryId?.let { id ->
            val sub = dao.category(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            if (sub.parent_id != category.id) throw DomainException(ErrorCode.FORMAT)
        }
        val delta = if (draft.type == BillType.EXPENSE) -draft.accountAmountMinor else draft.accountAmountMinor
        return Resolved(entity(draft, note).copy(
            currency_code = currency.code, cash_account_id = account?.id, account_currency_code = accountCurrency.code,
            rate = rate, rate_source = source.name, category_id = category.id, sub_category_id = draft.subCategoryId,
            count_in_stats = draft.countInStats,
        ), listOfNotNull(account?.let { Leg(CashSource.BILL, it.id, delta) }))
    }

    private suspend fun resolveTransfer(draft: BillDraft, note: String): Resolved {
        if (draft.categoryId != null || draft.subCategoryId != null || draft.discountMinor != 0L)
            throw DomainException(ErrorCode.FORMAT)
        R.check_nonnegative(draft.amountMinor, positive = true)
        if (draft.accountAmountMinor != draft.amountMinor) throw DomainException(ErrorCode.FORMAT)
        R.check_nonnegative(draft.feeMinor)
        val counterparty = BillRules.counterparty(draft.counterparty)
        val kind = draft.transferKind ?: throw DomainException(ErrorCode.FORMAT)
        val base = entity(draft, note).copy(transfer_kind = kind.name, count_in_stats = false)
        return when (kind) {
            TransferKind.INTERNAL -> {
                if (counterparty.isNotEmpty()) throw DomainException(ErrorCode.FORMAT)
                val from = account(draft.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT))
                val to = account(draft.toCashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT))
                if (from.id == to.id) throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
                val fromCurrency = cash.currency(from.currency_code)
                val toCurrency = cash.currency(to.currency_code)
                if (draft.currencyCode != fromCurrency.code) throw DomainException(ErrorCode.CURRENCY)
                val received = draft.toAmountMinor ?: throw DomainException(ErrorCode.FORMAT)
                val (rate, source) = checkedRate(draft.amountMinor, fromCurrency, received, toCurrency, draft.rate, draft.rateSource)
                Resolved(base.copy(currency_code = fromCurrency.code, cash_account_id = from.id,
                    account_currency_code = fromCurrency.code, rate = rate, rate_source = source.name,
                    to_cash_account_id = to.id, to_currency_code = toCurrency.code, to_amount_minor = received,
                    fee_minor = draft.feeMinor), listOf(
                    Leg(CashSource.BILL, from.id, -R.add(draft.amountMinor, draft.feeMinor)),
                    Leg(CashSource.BILL_TRANSFER_IN, to.id, received)))
            }
            TransferKind.TO_EXTERNAL -> {
                if (draft.toCashAccountId != null || draft.toAmountMinor != null) throw DomainException(ErrorCode.FORMAT)
                val from = account(draft.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT))
                val currency = cash.currency(from.currency_code)
                if (draft.currencyCode != currency.code) throw DomainException(ErrorCode.CURRENCY)
                checkedRate(draft.amountMinor, currency, draft.amountMinor, currency, draft.rate, draft.rateSource)
                Resolved(base.copy(currency_code = currency.code, cash_account_id = from.id,
                    account_currency_code = currency.code, counterparty = counterparty, fee_minor = draft.feeMinor),
                    listOf(Leg(CashSource.BILL, from.id, -R.add(draft.amountMinor, draft.feeMinor))))
            }
            TransferKind.FROM_EXTERNAL -> {
                if (draft.cashAccountId != null || draft.feeMinor != 0L) throw DomainException(ErrorCode.FORMAT)
                val to = account(draft.toCashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT))
                val currency = cash.currency(to.currency_code)
                if (draft.currencyCode != currency.code || draft.toAmountMinor != draft.amountMinor)
                    throw DomainException(ErrorCode.FORMAT)
                checkedRate(draft.amountMinor, currency, draft.amountMinor, currency, draft.rate, draft.rateSource)
                Resolved(base.copy(currency_code = currency.code, account_currency_code = currency.code,
                    to_cash_account_id = to.id, to_currency_code = currency.code, to_amount_minor = draft.amountMinor,
                    counterparty = counterparty), listOf(Leg(CashSource.BILL_TRANSFER_IN, to.id, draft.amountMinor)))
            }
        }
    }

    /**
     * The stored rate text and source for turning [fromMinor] of [from] into [toMinor] of [to]: 1 and SAME for one
     * currency; otherwise [toMinor] must be [fromMinor] at [rate] rounded half up, or, for a rate worked out from
     * the amount actually moved, [rate] must be that amount's rate.
     */
    private fun checkedRate(fromMinor: Long, from: Currency, toMinor: Long, to: Currency, rate: BigDecimal,
        source: BillRateSource): Pair<String, BillRateSource> {
        R.check_nonnegative(toMinor, positive = true)
        if (from == to) {
            if (rate.compareTo(BigDecimal.ONE) != 0 || toMinor != fromMinor) throw DomainException(ErrorCode.FORMAT)
            return "1" to BillRateSource.SAME
        }
        if (rate.signum() <= 0) throw DomainException(ErrorCode.POSITIVE)
        if (rate.stripTrailingZeros().scale() > BillRules.RATE_SCALE) throw DomainException(ErrorCode.PRECISION)
        if (rate.precision() > 40) throw DomainException(ErrorCode.OVERFLOW)
        when (source) {
            BillRateSource.SAME -> throw DomainException(ErrorCode.FORMAT)
            BillRateSource.DERIVED ->
                if (BillRules.derivedRate(fromMinor, from, toMinor, to).compareTo(rate) != 0) throw DomainException(ErrorCode.FORMAT)
            else -> if (BillRules.convert(fromMinor, from, to, rate) != toMinor) throw DomainException(ErrorCode.FORMAT)
        }
        return rate.stripTrailingZeros().toPlainString() to source
    }

    private suspend fun account(id: Long) = db.cash().cashAccount(id) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)

    /** The fields every bill type shares; the rest are filled in by type. */
    private fun entity(draft: BillDraft, note: String) = BillEntryEntity(
        operation_id = "", type = draft.type.name, occurred_at_ms = draft.occurredAtMs, amount_minor = draft.amountMinor,
        currency_code = draft.currencyCode, discount_minor = draft.discountMinor, cash_account_id = null,
        account_currency_code = draft.currencyCode, account_amount_minor = draft.accountAmountMinor, rate = "1",
        rate_source = BillRateSource.SAME.name, transfer_kind = null, to_cash_account_id = null, to_currency_code = null,
        to_amount_minor = null, fee_minor = 0, counterparty = "", category_id = null, sub_category_id = null,
        count_in_stats = draft.countInStats, reimbursable = false, reimburse_state = ReimburseState.NONE.name,
        parent_id = null, batch_id = null, note = note, revision = 1, is_deleted = false, created_at_ms = 0,
        updated_at_ms = 0)

    // Categories

    private suspend fun saveCategory(command: SaveBillCategory, now: Long): OperationResult {
        if (command.colorKey !in BillColors.keys || !ICON_KEY.matches(command.iconKey)) throw DomainException(ErrorCode.FORMAT)
        command.parentId?.let { parentId ->
            val parent = dao.category(parentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
            if (parent.kind != command.kind.name || parent.parent_id != null) throw DomainException(ErrorCode.FORMAT)
        }
        val old = command.categoryId?.let { id ->
            dao.category(id)?.takeIf { it.revision == command.expectedRevision } ?: throw DomainException(ErrorCode.STALE_RECORD)
        }
        if (old == null) {
            if (command.categoryId != null || command.expectedRevision != null) throw DomainException(ErrorCode.FORMAT)
            val name = BillRules.categoryName(command.name ?: throw DomainException(ErrorCode.NAME))
            val id = dao.insertCategory(BillCategoryEntity(kind = command.kind.name, parent_id = command.parentId,
                builtin_key = null, custom_name = name, icon_key = command.iconKey, color_key = command.colorKey,
                sort_order = dao.nextSortOrder(command.kind.name, command.parentId), hidden = false,
                last_cash_account_id = null, revision = 1, created_at_ms = now, updated_at_ms = now))
            return OperationResult("BILL_CATEGORY", id)
        }
        if (old.kind != command.kind.name || old.parent_id != command.parentId) throw DomainException(ErrorCode.FORMAT)
        val name = when {
            command.name != null -> BillRules.categoryName(command.name!!)
            old.builtin_key != null -> null
            else -> throw DomainException(ErrorCode.NAME)
        }
        if (dao.updateCategory(old.copy(custom_name = name, icon_key = command.iconKey, color_key = command.colorKey,
                revision = R.add(old.revision, 1), updated_at_ms = now)) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        return OperationResult("BILL_CATEGORY", old.id)
    }

    private suspend fun setHidden(command: SetBillCategoryHidden, now: Long): OperationResult {
        val old = dao.category(command.categoryId)?.takeIf { it.revision == command.expectedRevision }
            ?: throw DomainException(ErrorCode.STALE_RECORD)
        if (dao.updateCategory(old.copy(hidden = command.hidden, revision = R.add(old.revision, 1), updated_at_ms = now)) != 1)
            throw DomainException(ErrorCode.STALE_RECORD)
        return OperationResult("BILL_CATEGORY", old.id)
    }

    private suspend fun reorder(command: ReorderBillCategories, now: Long): OperationResult {
        val siblings = dao.siblings(command.kind.name, command.parentId)
        if (command.orderedIds.size != siblings.size || command.orderedIds.toSet() != siblings.map { it.id }.toSet())
            throw DomainException(ErrorCode.STALE_RECORD)
        command.orderedIds.forEachIndexed { index, id ->
            if (siblings.single { it.id == id }.sort_order != index.toLong()) dao.setCategoryOrder(id, index.toLong(), now)
        }
        return OperationResult("BILL_CATEGORY", command.parentId ?: command.orderedIds.firstOrNull() ?: 0)
    }

    private suspend fun deleteCategory(command: DeleteBillCategory, now: Long): OperationResult {
        val old = dao.category(command.categoryId)?.takeIf { it.revision == command.expectedRevision }
            ?: throw DomainException(ErrorCode.STALE_RECORD)
        if (old.builtin_key in BillCategoryPresets.PROTECTED) throw DomainException(ErrorCode.CATEGORY_PROTECTED)
        // Hidden subcategories count: they still hold bills and would otherwise be left without a parent.
        if (old.parent_id == null && dao.siblings(old.kind, old.id).isNotEmpty())
            throw DomainException(ErrorCode.CATEGORY_HAS_SUBCATEGORIES)
        val column = if (old.parent_id == null) "category_id" else "sub_category_id"
        val sql = db.openHelper.writableDatabase
        val bills = dao.billsOnCategory(old.id)
        when {
            command.deleteBills -> {
                if (command.moveToCategoryId != null) throw DomainException(ErrorCode.FORMAT)
                val title = BillRules.note(command.billTitle)
                bills.forEach { retire(it, it.note.ifBlank { title }, now) }
            }
            bills.isNotEmpty() -> {
                val target = dao.category(command.moveToCategoryId ?: throw DomainException(ErrorCode.FORMAT))
                    ?: throw DomainException(ErrorCode.NOT_FOUND)
                if (target.kind != old.kind || target.id == old.id) throw DomainException(ErrorCode.FORMAT)
                val (category, sub) = if (target.parent_id == null) target.id to null else target.parent_id to target.id
                // Refunds and reimbursements carry their bill's category, so they move with it.
                sql.execSQL("""UPDATE bill_entries SET category_id=?,sub_category_id=?,revision=revision+1,updated_at_ms=?
                    WHERE is_deleted=0 AND $column=?""", arrayOf(category, sub, now, old.id))
            }
            command.moveToCategoryId != null -> throw DomainException(ErrorCode.FORMAT)
        }
        // Bills deleted before still name the category; they are filed under the kind's catch-all, which always stays.
        if (dao.categoryReferences(old.id) > 0) {
            val (top, sub) = BillCategoryPresets.fallback(BillCategoryKind.valueOf(old.kind))
            val topId = dao.presetId(top) ?: throw DomainException(ErrorCode.NOT_FOUND)
            val subId = sub?.let { dao.presetId(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
            sql.execSQL("UPDATE bill_entries SET category_id=?,sub_category_id=? WHERE is_deleted=1 AND $column=?",
                arrayOf(topId, subId, old.id))
        }
        if (dao.deleteCategory(old.id) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        // Close the gap the category leaves among its siblings.
        dao.siblings(old.kind, old.parent_id).forEachIndexed { index, row ->
            if (row.sort_order != index.toLong()) dao.setCategoryOrder(row.id, index.toLong(), now)
        }
        return OperationResult("BILL_CATEGORY", old.id)
    }

    private suspend fun saveFavorites(command: SaveBillFavorites): OperationResult {
        val ids = command.orderedCashAccountIds
        if (ids.size != ids.toSet().size) throw DomainException(ErrorCode.FORMAT)
        ids.forEach { account(it) }
        dao.clearFavorites()
        ids.forEachIndexed { index, id -> dao.setFavorite(id, index.toLong()) }
        return OperationResult("BALANCE_ACCOUNT", ids.firstOrNull() ?: 0)
    }

    private companion object {
        val EDITABLE = setOf(BillType.EXPENSE.name, BillType.INCOME.name, BillType.TRANSFER.name)
        val ICON_KEY = Regex("[a-z0-9][a-z0-9-]{0,47}")
    }
}

/** A bill's sides as cash entries, for checks outside the handler. */
internal fun CashEntryEntity.isBillLeg(): Boolean =
    source_kind == CashSource.BILL.name || source_kind == CashSource.BILL_TRANSFER_IN.name
