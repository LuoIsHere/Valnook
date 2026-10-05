package dev.valnook.data.portability

import dev.valnook.data.database.STATISTICS_RULE_VERSION
import dev.valnook.domain.calculation.InvestmentProfitCalculator

data class AppBuildInfo(
    val applicationFamily: String,
    val appVersion: String,
    val appVersionCode: Long,
    val internalBuildRevision: String,
    val internalBuildLabel: String,
    val databaseSchemaVersion: Int
)

data class BackupReadLimits(
    val maxArchiveBytes: Long = 256L * 1024L * 1024L,
    val maxExpandedBytes: Long = 1024L * 1024L * 1024L,
    val maxPayloadBytes: Long = 512L * 1024L * 1024L,
    val maxManifestBytes: Long = 1024L * 1024L,
    val maxJsonLineBytes: Int = 1024 * 1024,
    val maxRecords: Long = 2_000_000L,
    val maxJsonDepth: Int = 32,
    val maxEntries: Int = 64
)

enum class PortableKind { LONG, TEXT, BOOLEAN, LOCAL_DATE }

data class PortableColumn(
    val name: String,
    val kind: PortableKind,
    val nullable: Boolean = false,
    val databaseName: String = name
)

data class PortableTable(
    val path: String,
    val table: String,
    val columns: List<PortableColumn>,
    val orderBy: String
) {
    val selectList: String = columns.joinToString(",") { column ->
        if (column.name == column.databaseName) column.databaseName
        else "${column.databaseName} AS ${column.name}"
    }
    val databaseColumns: List<String> = columns.map { it.databaseName }
}

internal object BackupContract {
    const val FORMAT = "valnook-backup"
    const val FORMAT_VERSION = 1
    const val DATA_SCHEMA_VERSION = 2
    const val REPORT_FORMAT_VERSION = 2
    const val POSITION_COST_RULE = InvestmentProfitCalculator.ALGORITHM_VERSION
    const val DEPOSIT_INTEREST_RULE = 1
    const val HISTORICAL_VALUATION_RULE = STATISTICS_RULE_VERSION

    val requiredFeatures = listOf("audit-v1", "overwrite-restore-v1", "portable-model-v1", "credit-accounts-v1")

    private fun long(name: String, nullable: Boolean = false) = PortableColumn(name, PortableKind.LONG, nullable)
    private fun text(name: String, nullable: Boolean = false) = PortableColumn(name, PortableKind.TEXT, nullable)
    private fun bool(name: String) = PortableColumn(name, PortableKind.BOOLEAN)
    private fun localDate(name: String, databaseName: String) =
        PortableColumn(name, PortableKind.LOCAL_DATE, databaseName = databaseName)

    val tables = listOf(
        PortableTable("data/accounts.jsonl", "savings_accounts", listOf(
            long("id"), text("name"), text("note"), long("created_at_ms"), long("updated_at_ms"), long("revision")
        ), "id"),
        PortableTable("data/cash_accounts.jsonl", "cash_accounts", listOf(
            long("savings_account_id"), text("currency_code"), long("balance_minor"), long("revision"),
            long("updated_at_ms"), long("id"), text("name"), text("note"), bool("currency_locked"), long("created_at_ms")
        ), "savings_account_id,id"),
        PortableTable("data/credit_account_profiles.jsonl", "credit_account_profiles", listOf(
            long("account_id"), long("credit_limit_minor", true), long("statement_day"),
            text("due_rule_type"), long("due_rule_value"), long("limit_source_account_id", true)
        ), "account_id"),
        PortableTable("data/operation_receipts.jsonl", "operations", listOf(
            text("operation_id"), text("kind"), text("request_fingerprint"), text("result_kind", true),
            long("result_id", true), long("created_at_ms")
        ), "created_at_ms,operation_id"),
        PortableTable("data/cash_entries.jsonl", "cash_entries", listOf(
            long("id"), text("original_operation_id"), long("savings_account_id"), text("currency_code"),
            long("cash_account_id"), text("source_kind"), long("source_id", true), long("delta_minor"),
            long("occurred_at_ms"), text("note"), long("revision"), bool("is_deleted"),
            long("created_at_ms"), long("updated_at_ms")
        ), "occurred_at_ms,id"),
        PortableTable("data/cash_movements.jsonl", "cash_movements", listOf(
            long("id"), text("operation_id"), long("savings_account_id"), text("currency_code"),
            long("cash_account_id"), text("reason"), long("delta_minor"), long("balance_before_minor"),
            long("balance_after_minor"), long("created_at_ms")
        ), "created_at_ms,id"),
        PortableTable("data/term_deposits.jsonl", "term_deposits", listOf(
            long("id"), long("savings_account_id"), text("currency_code"), long("principal_minor"),
            long("annual_rate_percent_e8"), localDate("start_date", "start_epoch_day"),
            localDate("end_date", "end_epoch_day"), text("interest_rule"),
            long("calculation_version"), text("rounding_mode"), long("expected_interest_minor"), text("status"),
            bool("open_cash_linked"), PortableColumn("close_cash_linked", PortableKind.BOOLEAN, true),
            long("open_cash_account_id", true), long("close_cash_account_id", true), text("open_operation_id"),
            text("close_operation_id", true), long("closed_at_ms", true), long("created_at_ms"),
            long("updated_at_ms"), long("revision")
        ), "start_epoch_day,id"),
        PortableTable("data/asset_classes.jsonl", "asset_types", listOf(
            long("id"), text("name"), text("normalized_name"), long("created_at_ms"), long("updated_at_ms")
        ), "id"),
        PortableTable("data/instruments.jsonl", "instruments", listOf(
            long("id"), long("asset_type_id"), text("name"), text("symbol"), text("currency_code"),
            long("current_price_e5"), bool("currency_locked"), long("revision"), bool("symbol_locked"),
            long("price_updated_at_ms"), long("created_at_ms"), long("updated_at_ms")
        ), "id"),
        // Format v1 keeps the historical filename, but each row is the complete account-position container.
        PortableTable("data/opening_positions.jsonl", "investments", listOf(
            long("id"), long("savings_account_id"), long("instrument_id"), long("opening_quantity_e8"),
            long("holding_quantity_e8"), long("revision"), long("created_at_ms"), long("updated_at_ms"),
            long("opening_cost_price_e8", true), long("opening_at_ms"), text("remaining_cost", true),
            text("realized_profit", true), bool("chronology_valid"), long("algorithm_version"),
            text("position_state"), long("last_activity_at_ms")
        ), "savings_account_id,instrument_id,id"),
        PortableTable("data/investment_trades.jsonl", "investment_trades", listOf(
            long("id"), long("investment_id"), text("operation_id"), text("direction"), long("quantity_e8"),
            long("execution_price_e8"), long("amount_minor"), text("currency_code"), bool("cash_linked"),
            long("cash_account_id", true), long("occurred_at_ms"), long("created_at_ms"), long("revision"),
            bool("is_deleted"), long("updated_at_ms"), long("fee_minor")
        ), "investment_id,occurred_at_ms,id"),
        PortableTable("data/price_history.jsonl", "instrument_price_history", listOf(
            long("id"), long("instrument_id"), long("price_e5"), text("currency_code"), long("effective_at_ms"),
            long("created_at_ms"), long("updated_at_ms"), long("revision"), bool("is_deleted")
        ), "instrument_id,effective_at_ms,id"),
        PortableTable("data/audit_events.jsonl", "audit_events", listOf(
            text("event_id"), long("event_schema_version"), text("correlation_id"),
            text("operation_id", true), text("action"), text("entity_kind"),
            text("entity_id", true), long("business_at_ms", true), text("business_local_date", true),
            long("recorded_at_ms"),
            text("before_json", true), text("after_json", true), text("changed_fields_json"),
            text("cash_effects_json"), text("source")
        ), "recorded_at_ms,event_id"),
        PortableTable("data/audit_event_accounts.jsonl", "audit_event_accounts", listOf(
            text("event_id"), long("account_id")
        ), "event_id,account_id")
    )

    val specialPaths = setOf(
        "data/currencies.json",
        "data/valuation_baselines.jsonl",
        "data/audit_metadata.json",
        "preferences/financial.json",
        "preferences/appearance.json",
        "verification/snapshot_totals.json"
    )

    fun tablesFor(dataSchemaVersion: Int): List<PortableTable> = when (dataSchemaVersion) {
        1 -> tables.filterNot { it.table == "credit_account_profiles" }
        2 -> tables
        else -> emptyList()
    }

    fun payloadPathsFor(dataSchemaVersion: Int): Set<String> =
        tablesFor(dataSchemaVersion).mapTo(linkedSetOf()) { it.path } + specialPaths

    val payloadPaths: Set<String> = payloadPathsFor(DATA_SCHEMA_VERSION)
    val zipPaths: Set<String> = payloadPaths + "manifest.json"

    val importOrder = listOf(
        "savings_accounts", "operations", "asset_types", "instruments", "cash_accounts", "credit_account_profiles", "investments",
        "term_deposits", "investment_trades", "cash_entries", "cash_movements", "instrument_price_history",
        "statistics_state", "statistics_baseline_items", "app_settings", "fx_rates", "audit_metadata",
        "audit_events", "audit_event_accounts"
    )
}
