package dev.valnook.data.webadmin

import android.database.Cursor
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.CurrentStatistics
import dev.valnook.domain.model.StatisticsRequest
import dev.valnook.domain.model.StatisticsSeries
import dev.valnook.domain.repository.OverviewRepository
import dev.valnook.domain.repository.DepositRepository
import dev.valnook.domain.repository.InvestmentRepository
import dev.valnook.domain.repository.StatisticsRepository
import dev.valnook.domain.webadmin.WebAdminReadRepository
import dev.valnook.domain.webadmin.WebRecord
import dev.valnook.domain.webadmin.WebRecordCursor
import dev.valnook.domain.webadmin.WebRecordFilter
import dev.valnook.domain.webadmin.WebRecordKind
import dev.valnook.domain.webadmin.WebRecordPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.math.BigDecimal

/** Read-only projections for the Web API. Mutations remain exclusively in FinancialCommands. */
class RoomWebAdminReadRepository(
    private val database: ValnookDatabase,
    private val overview: OverviewRepository,
    private val statistics: StatisticsRepository,
    private val deposits: DepositRepository,
    private val investments: InvestmentRepository
) : WebAdminReadRepository {
    override suspend fun accountIconImage(key: String): ByteArray? = database.accounts().iconImage(key)

    override suspend fun generation(): Long = database.audit().generation()

    override suspend fun snapshot(): AssetSnapshot = overview.snapshot()

    override suspend fun assetTypes() = investments.observe_types().first()

    override suspend fun deposits() = overview.snapshot().accounts.flatMap { account ->
        deposits.observe_deposits(account.id, 10_000, closed = false).first() +
            deposits.observe_deposits(account.id, 10_000, closed = true).first()
    }

    override suspend fun currentStatistics(): CurrentStatistics = statistics.loadCurrent()

    override suspend fun statisticsSeries(request: StatisticsRequest): StatisticsSeries =
        statistics.loadSeries(request)

    override suspend fun records(filter: WebRecordFilter): WebRecordPage = withContext(Dispatchers.IO) {
        val size = filter.pageSize.coerceIn(1, 100)
        val query = filter.query.trim().take(100)
        val args = mutableListOf<Any?>()
        val conditions = mutableListOf<String>()
        filter.fromMs?.let { conditions += "business_ms>=?"; args += it }
        filter.toMs?.let { conditions += "business_ms<=?"; args += it }
        filter.accountId?.let { conditions += "account_id=?"; args += it }
        filter.childId?.let { conditions += "child_id=?"; args += it }
        filter.kind?.let { conditions += "kind=?"; args += it.name }
        if (query.isNotEmpty()) {
            conditions += "search_text LIKE ? ESCAPE '\\'"
            args += "%${escapeLike(query.lowercase())}%"
        }
        filter.cursor?.let {
            conditions += "(business_ms<? OR (business_ms=? AND stable_key<?))"
            args += it.businessAtMs
            args += it.businessAtMs
            args += it.stableKey
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        args += size + 1
        val sql = """
            WITH records AS (
                SELECT 'CASH' kind,e.id id,e.revision revision,e.occurred_at_ms business_ms,
                    e.savings_account_id account_id,a.name account_name,e.cash_account_id child_id,
                    c.name child_name,e.source_kind action,
                    CASE WHEN e.source_kind='TRADE' THEN COALESCE(i.name,'')
                         WHEN e.source_kind IN ('TERM_OPEN','TERM_CLOSE') THEN 'Term deposit'
                         ELSE c.name END object_name,
                    e.delta_minor raw_amount,NULL raw_quantity,NULL raw_unit_price,NULL raw_fee,
                    NULL cash_linked,NULL linked_cash_account_id,e.source_id source_id,p.id source_parent_id,
                    c.currency_code currency_code,e.note note,
                    e.updated_at_ms updated_ms,curr.fraction_digits fraction_digits,
                    'CASH:' || printf('%020d',e.id) stable_key,
                    lower(a.name || ' ' || c.name || ' ' || COALESCE(i.name,'') || ' ' || e.note) search_text
                FROM cash_entries e
                JOIN savings_accounts a ON a.id=e.savings_account_id
                JOIN cash_accounts c ON c.id=e.cash_account_id
                JOIN currencies curr ON curr.code=c.currency_code
                LEFT JOIN investment_trades linked_trade ON e.source_kind='TRADE' AND linked_trade.id=e.source_id
                LEFT JOIN investments p ON p.id=linked_trade.investment_id
                LEFT JOIN instruments i ON i.id=p.instrument_id
                WHERE e.is_deleted=0
                UNION ALL
                SELECT 'DEPOSIT',d.id,d.revision,d.start_epoch_day*86400000,d.savings_account_id,
                    a.name,d.id,d.currency_code,CASE WHEN d.status='CLOSED' THEN 'CLOSE' ELSE 'OPEN' END,
                    d.currency_code,d.principal_minor,NULL,NULL,NULL,NULL,NULL,d.id,NULL,d.currency_code,'',d.updated_at_ms,curr.fraction_digits,
                    'DEPOSIT:' || printf('%020d',d.id),lower(a.name || ' ' || d.currency_code)
                FROM term_deposits d
                JOIN savings_accounts a ON a.id=d.savings_account_id
                JOIN currencies curr ON curr.code=d.currency_code
                UNION ALL
                SELECT 'TRADE',t.id,t.revision,t.occurred_at_ms,p.savings_account_id,a.name,p.id,
                    i.name,t.direction,i.name,t.amount_minor,t.quantity_e8,t.execution_price_e8,t.fee_minor,
                    t.cash_linked,t.cash_account_id,t.id,p.id,t.currency_code,i.symbol,t.updated_at_ms,
                    curr.fraction_digits,'TRADE:' || printf('%020d',t.id),
                    lower(a.name || ' ' || i.name || ' ' || i.symbol || ' ' || t.direction)
                FROM investment_trades t
                JOIN investments p ON p.id=t.investment_id
                JOIN instruments i ON i.id=p.instrument_id
                JOIN savings_accounts a ON a.id=p.savings_account_id
                JOIN currencies curr ON curr.code=t.currency_code
                WHERE t.is_deleted=0
            )
            SELECT * FROM records $where ORDER BY business_ms DESC,stable_key DESC LIMIT ?
        """.trimIndent()
        val rows = mutableListOf<Pair<WebRecord, String>>()
        database.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.record() to cursor.string("stable_key")
        }
        val page = rows.take(size)
        WebRecordPage(page.map { it.first }, if (rows.size > size) page.lastOrNull()?.let {
            WebRecordCursor(it.first.businessAtMs, it.second)
        } else null)
    }

    private fun Cursor.record(): WebRecord {
        val currency = Currency.of(string("currency_code"))
        val amount = BigDecimal.valueOf(long("raw_amount"), int("fraction_digits"))
            .stripTrailingZeros().toPlainString()
        return WebRecord(
            kind = WebRecordKind.valueOf(string("kind")),
            id = long("id"),
            revision = long("revision"),
            businessAtMs = long("business_ms"),
            accountId = long("account_id"),
            accountName = string("account_name"),
            childId = index("child_id").let { if (isNull(it)) null else getLong(it) },
            childName = string("child_name"),
            action = string("action"),
            objectName = string("object_name"),
            amount = amount,
            currencyCode = currency.code,
            quantity = nullableLong("raw_quantity")?.let { BigDecimal.valueOf(it, 8).stripTrailingZeros().toPlainString() },
            unitPrice = nullableLong("raw_unit_price")?.let { BigDecimal.valueOf(it, 8).stripTrailingZeros().toPlainString() },
            fee = nullableLong("raw_fee")?.let { BigDecimal.valueOf(it, currency.fraction_digits).stripTrailingZeros().toPlainString() },
            cashLinked = nullableLong("cash_linked")?.let { it != 0L },
            linkedCashAccountId = nullableLong("linked_cash_account_id"),
            sourceId = nullableLong("source_id"),
            sourceParentId = nullableLong("source_parent_id"),
            note = string("note"),
            updatedAtMs = long("updated_ms")
        )
    }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\")
        .replace("%", "\\%").replace("_", "\\_")

    private fun Cursor.index(name: String): Int = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String): String = getString(index(name)).orEmpty()
    private fun Cursor.long(name: String): Long = getLong(index(name))
    private fun Cursor.nullableLong(name: String): Long? = index(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String): Int = getInt(index(name))
}
