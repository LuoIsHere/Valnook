package dev.valnook.data.portability

import android.database.Cursor
import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomOverview
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.model.Currency
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class WorkbookPackage(
    val file: File,
    val fileName: String,
    val recordCount: Long,
    val createdAtUtc: String,
    val sha256: String
)

internal class SpreadsheetWriter(
    private val db: ValnookDatabase,
    private val clock: Clock,
    private val buildInfo: AppBuildInfo,
    private val workRoot: File
) {
    suspend fun create(reportId: String, language: AppLanguage, demo: Boolean): WorkbookPackage {
        runCatching { UUID.fromString(reportId) }.getOrElse {
            throw PortabilityException(PortabilityErrorCode.INVALID_DATA, it)
        }
        val english = language == AppLanguage.ENGLISH ||
            (language == AppLanguage.SYSTEM && Locale.getDefault().language != "zh")
        val work = File(workRoot, "xlsx-${UUID.randomUUID()}").also(BackupSnapshotWriter::secureDirectory)
        val sheetsDir = File(work, "sheets").also(BackupSnapshotWriter::secureDirectory)
        val createdAt = clock.instant()
        try {
            var recordCount = 0L
            val sheets = mutableListOf<SheetDefinition>()
            db.withTransaction {
                val snapshot = RoomOverview(db).snapshot()
                val names = sheetNames(snapshot, english)
                val summary = File(sheetsDir, "sheet1.xml")
                writeSummary(summary, snapshot, names, english, demo, createdAt)
                sheets += SheetDefinition(names.summary, summary)
                snapshot.accounts.forEachIndexed { index, account ->
                    val asset = File(sheetsDir, "sheet${sheets.size + 1}.xml")
                    writeAssets(asset, snapshot, account.id, english, createdAt)
                    sheets += SheetDefinition(names.accounts[index].first, asset)
                    val records = File(sheetsDir, "sheet${sheets.size + 1}.xml")
                    recordCount += writeRecords(records, account.id, english)
                    sheets += SheetDefinition(names.accounts[index].second, records)
                }
            }
            val target = File(work, "report.xlsx")
            packageWorkbook(target, sheets)
            val prefix = if (demo) "Valnook_Demo" else "Valnook"
            val fileName = "${prefix}_${FILE_TIME.format(createdAt)}_${reportId.take(8)}.xlsx"
            return WorkbookPackage(target, fileName, recordCount, BackupSnapshotWriter.formatUtc(createdAt),
                BackupSnapshotWriter.sha256(target))
        } catch (error: Exception) {
            work.deleteRecursively()
            throw error
        }
    }

    private fun writeSummary(
        file: File,
        snapshot: AssetSnapshot,
        names: WorkbookNames,
        english: Boolean,
        demo: Boolean,
        createdAt: Instant
    ) {
        val valuation = AssetValuation.calculate(snapshot)
        val base = snapshot.settings.baseCurrency?.code ?: label(english, "未设置", "Not set")
        val metadata = db.openHelper.readableDatabase.query(
            "SELECT tracking_start_ms,complete_since_start,legacy_history_before_start FROM audit_metadata WHERE id=1"
        ).use { cursor -> if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getInt(1) != 0, cursor.getInt(2) != 0) else null }
        SheetXml(file, freezeRows = 1).use { sheet ->
            sheet.row(title(label(english, "Valnook 资产汇总", "Valnook asset summary")))
            sheet.row(header(label(english, "项目", "Item")), header(label(english, "值", "Value")))
            sheet.row(text(label(english, "用途", "Purpose")), text(label(english,
                "仅供分析，不能导入 Valnook", "For analysis only. Cannot be restored into Valnook.")))
            sheet.row(text(label(english, "数据类型", "Data type")), text(if (demo)
                label(english, "合成演示数据", "Synthetic demo data") else label(english, "真实模式快照", "Real-mode snapshot")))
            sheet.row(text(label(english, "导出时间（UTC）", "Export time (UTC)")), text(BackupSnapshotWriter.formatUtc(createdAt)))
            sheet.row(text(label(english, "时间来源", "Time source")), text("DEVICE_CLOCK"))
            sheet.row(text(label(english, "应用版本", "App version")), text(buildInfo.appVersion))
            sheet.row(text(label(english, "内部编号", "Internal revision")), text(buildInfo.internalBuildRevision))
            sheet.row(text(label(english, "数据库版本", "Database version")), number(buildInfo.databaseSchemaVersion.toString()))
            sheet.row(text(label(english, "报表格式", "Report format")), number(BackupContract.REPORT_FORMAT_VERSION.toString()))
            sheet.row(text(label(english, "主币种", "Base currency")), text(base))
            sheet.row(text(label(english, "缺失汇率口径", "Missing FX rule")), text(label(english,
                "未配置的非主币种按 1:1", "Unconfigured non-base currencies use 1:1")))
            snapshot.settings.rates.sortedWith(compareBy({ it.sourceCurrency.code }, { it.targetCurrency.code })).forEach { rate ->
                sheet.row(text(label(english, "手动汇率", "Manual FX rate")),
                    text("${rate.sourceCurrency.code}/${rate.targetCurrency.code}"), decimal(rate.rate))
            }
            sheet.blank()
            sheet.row(section(label(english, "当前汇总（主币种）", "Current totals (base currency)")))
            sheet.row(header(label(english, "类别", "Category")), header(label(english, "金额", "Amount")), header(label(english, "币种", "Currency")))
            sheet.row(text(label(english, "现金", "Cash")), decimal(valuation.cash.amount), text(base))
            sheet.row(text(label(english, "未结束存单本金", "Open deposit principal")), decimal(valuation.depositValue.amount), text(base))
            sheet.row(text(label(english, "当前投资市值", "Current investment value")), decimal(valuation.investmentValue.amount), text(base))
            sheet.row(text(label(english, "总资产", "Total assets")), decimal(valuation.total.amount), text(base))
            sheet.row(text(label(english, "主账户数", "Account count")), number(snapshot.accounts.size.toString()))
            sheet.row(text(label(english, "现金账户数", "Cash account count")), number(snapshot.cash.size.toString()))
            sheet.blank()
            sheet.row(section(label(english, "审计覆盖", "Audit coverage")))
            sheet.row(text(label(english, "覆盖起点", "Coverage start")), text(metadata?.first?.let(::instantText) ?: ""))
            sheet.row(text(label(english, "覆盖说明", "Coverage note")), text(if (metadata == null || !metadata.second || metadata.third)
                label(english, "升级前历史可能没有修改前后值；已有事实不伪造审计。", "Pre-upgrade history may lack before/after values; existing facts are not fabricated as audit.")
                else label(english, "从覆盖起点起完整", "Complete since the coverage start")))
            sheet.blank()
            sheet.row(section(label(english, "主账户索引", "Account index")))
            sheet.row(header(label(english, "序号", "No.")), header(label(english, "账户", "Account")),
                header(label(english, "备注", "Note")), header(label(english, "资产表", "Assets sheet")),
                header(label(english, "记录表", "Records sheet")))
            snapshot.accounts.forEachIndexed { index, account ->
                val pair = names.accounts[index]
                sheet.row(number((index + 1).toString()), text(account.name), text(account.note),
                    link(pair.first, internalLocation(pair.first)), link(pair.second, internalLocation(pair.second)))
            }
        }
    }

    private fun writeAssets(file: File, snapshot: AssetSnapshot, accountId: Long, english: Boolean, createdAt: Instant) {
        val account = snapshot.accounts.first { it.id == accountId }
        SheetXml(file, freezeRows = 1).use { sheet ->
            sheet.row(title(label(english, "主账户资产", "Account assets")))
            sheet.row(text(label(english, "账户", "Account")), text(account.name))
            sheet.row(text(label(english, "备注", "Note")), text(account.note))
            sheet.row(text(label(english, "快照时间（UTC）", "Snapshot time (UTC)")), text(BackupSnapshotWriter.formatUtc(createdAt)))
            sheet.blank()
            sheet.row(section(label(english, "现金账户", "Cash accounts")))
            sheet.row(header(label(english, "名称", "Name")), header(label(english, "备注", "Note")),
                header(label(english, "币种", "Currency")), header(label(english, "余额", "Balance")),
                header(label(english, "主币种折算", "Base-currency value")))
            snapshot.cash.filter { it.account_id == accountId }.forEach { cash ->
                sheet.row(text(cash.name), text(cash.note), text(cash.currency.code),
                    minor(cash.balance_minor, cash.currency), decimal(convert(snapshot, cash.currency,
                        BigDecimal.valueOf(cash.balance_minor, cash.currency.fraction_digits))))
            }
            sheet.blank()
            sheet.row(section(label(english, "定期存单", "Term deposits")))
            sheet.row(header("ID"), header(label(english, "币种", "Currency")), header(label(english, "本金", "Principal")),
                header(label(english, "开始日期", "Start date")), header(label(english, "结束日期", "End date")),
                header(label(english, "年利率 (%)", "Annual rate (%)")), header(label(english, "预计利息", "Expected interest")),
                header(label(english, "状态", "Status")), header(label(english, "开立现金账户", "Opening cash account")),
                header(label(english, "结束现金账户", "Closing cash account")))
            val cashNames = snapshot.cash.associate { it.id to it.name }
            query("""SELECT id,currency_code,principal_minor,start_epoch_day,end_epoch_day,
                annual_rate_percent_e8,expected_interest_minor,status,open_cash_account_id,close_cash_account_id
                FROM term_deposits WHERE savings_account_id=? ORDER BY start_epoch_day,id""", accountId).use { cursor ->
                while (cursor.moveToNext()) {
                    val currency = Currency.of(cursor.getString(1))
                    sheet.row(id(cursor.getLong(0)), text(currency.code), minor(cursor.getLong(2), currency),
                        text(LocalDate.ofEpochDay(cursor.getLong(3)).toString()), text(LocalDate.ofEpochDay(cursor.getLong(4)).toString()),
                        decimal(BigDecimal.valueOf(cursor.getLong(5), 8)), minor(cursor.getLong(6), currency),
                        text(cursor.getString(7)), text(if (cursor.isNull(8)) "" else cashNames[cursor.getLong(8)].orEmpty()),
                        text(if (cursor.isNull(9)) "" else cashNames[cursor.getLong(9)].orEmpty()))
                }
            }
            sheet.blank()
            sheet.row(section(label(english, "持仓与历史", "Holdings and history")))
            sheet.row(header(label(english, "状态", "State")), header(label(english, "名称", "Name")), header(label(english, "代码", "Symbol")),
                header(label(english, "类别", "Class")), header(label(english, "币种", "Currency")), header(label(english, "数量", "Quantity")),
                header(label(english, "当前价", "Current price")), header(label(english, "持仓均价", "Average cost")),
                header(label(english, "持仓成本", "Remaining cost")), header(label(english, "市值", "Market value")),
                header(label(english, "未实现盈亏", "Unrealized P/L")), header(label(english, "已实现盈亏", "Realized P/L")))
            snapshot.positions.filter { it.account_id == accountId }.forEach { position ->
                val profit = InvestmentProfitCalculator.fromReadModel(position)
                val value = AssetValuation.marketValue(position)
                sheet.row(text(if (position.holding_quantity_e8 == 0L) label(english, "零持仓历史", "Zero-holding history")
                    else label(english, "当前持仓", "Current holding")), text(position.name), text(position.symbol),
                    text(position.type_name), text(position.currency.code), decimal(BigDecimal.valueOf(position.holding_quantity_e8, 8)),
                    decimal(BigDecimal.valueOf(position.current_price_e8, 8)), valueOrBlank(profit.average_cost),
                    valueOrBlank(profit.remainingCost), decimal(value), valueOrBlank(profit.unrealized), valueOrBlank(profit.realized))
            }
        }
    }

    private fun writeRecords(file: File, accountId: Long, english: Boolean): Long {
        var count = 0L
        SheetXml(file, freezeRows = 1, autoFilter = true).use { sheet ->
            sheet.row(
                header(label(english, "操作时间", "Operation time")), header(label(english, "业务时间", "Business time")),
                header(label(english, "操作类型", "Operation type")), header(label(english, "子账户", "Subaccount")),
                header(label(english, "对象", "Object")), header(label(english, "币种", "Currency")),
                header(label(english, "数量", "Quantity")), header(label(english, "成交单价", "Unit price")),
                header(label(english, "手续费", "Fee")), header(label(english, "金额", "Amount")),
                header(label(english, "修改摘要", "Change summary")), header(label(english, "修改前", "Before")),
                header(label(english, "修改后", "After")), header(label(english, "现金影响", "Cash effects")),
                header(label(english, "来源 / 状态", "Source / state")), header(label(english, "事件 ID", "Event ID")),
                header(label(english, "关联 ID", "Related ID"))
            )
            recordCursor(accountId).use { cursor ->
                while (cursor.moveToNext()) {
                    val businessValue = cursor.getString(1).orEmpty()
                    val currencyCode = cursor.getString(5).orEmpty()
                    val currency = Currency.supported.firstOrNull { it.code == currencyCode }
                    sheet.row(
                        text(instantText(cursor.getLong(0))),
                        text(when {
                            businessValue.isBlank() -> ""
                            businessValue.contains('-') -> businessValue
                            else -> businessValue.toLongOrNull()?.let(::instantText).orEmpty()
                        }),
                        text(cursor.getString(2)), text(cursor.getString(3).orEmpty()), text(cursor.getString(4).orEmpty()),
                        text(currencyCode), scaled(cursor.getString(6).orEmpty(), 8), scaled(cursor.getString(7).orEmpty(), 8),
                        currencyScaled(cursor.getString(8).orEmpty(), currency), currencyScaled(cursor.getString(9).orEmpty(), currency),
                        text(cursor.getString(10).orEmpty()),
                        text(cursor.getString(11).orEmpty()), text(cursor.getString(12).orEmpty()), text(cursor.getString(13).orEmpty()),
                        text(cursor.getString(14).orEmpty()), text(cursor.getString(15).orEmpty()), text(cursor.getString(16).orEmpty())
                    )
                    count++
                }
            }
        }
        return count
    }

    private fun recordCursor(accountId: Long): Cursor = db.openHelper.readableDatabase.query("""
        SELECT operation_time,business_time,operation_type,subaccount,object_name,currency,quantity,unit_price,fee,amount,
            change_summary,before_value,after_value,cash_effects,source_state,event_id,related_id FROM (
        SELECT e.recorded_at_ms operation_time,
            COALESCE(e.business_local_date,CAST(e.business_at_ms AS TEXT),'') business_time,
            e.action||' '||e.entity_kind operation_type,
            COALESCE(json_extract(e.after_json,'$.audit_cash_account_name'),'') subaccount,
            CASE WHEN json_extract(e.after_json,'$.audit_instrument_name') IS NOT NULL
                THEN json_extract(e.after_json,'$.audit_instrument_name')||' / '||
                    COALESCE(json_extract(e.after_json,'$.audit_instrument_symbol'),'')
                ELSE COALESCE(json_extract(e.after_json,'$.name'),e.entity_id,'') END object_name,
            COALESCE(json_extract(e.after_json,'$.currency_code'),
                json_extract(e.after_json,'$.audit_instrument_currency'),
                json_extract(e.after_json,'$.audit_cash_currency'),
                json_extract(e.after_json,'$.audit_deposit_currency'),'') currency,
            COALESCE(json_extract(e.after_json,'$.quantity_e8'),'') quantity,
            COALESCE(json_extract(e.after_json,'$.execution_price_e8'),'') unit_price,
            COALESCE(json_extract(e.after_json,'$.fee_minor'),'') fee,
            COALESCE(json_extract(e.after_json,'$.amount_minor'),json_extract(e.after_json,'$.delta_minor'),
                json_extract(e.after_json,'$.principal_minor'),'') amount,
            e.changed_fields_json change_summary,COALESCE(e.before_json,'') before_value,COALESCE(e.after_json,'') after_value,
            e.cash_effects_json cash_effects,e.source source_state,e.event_id event_id,COALESCE(e.entity_id,'') related_id,
            'A'||e.event_id stable_id
        FROM audit_events e JOIN audit_event_accounts a ON a.event_id=e.event_id WHERE a.account_id=?
        UNION ALL
        SELECT t.created_at_ms,CAST(t.occurred_at_ms AS TEXT),t.direction,c.name,i.name||' / '||i.symbol,t.currency_code,
            CAST(t.quantity_e8 AS TEXT),CAST(t.execution_price_e8 AS TEXT),CAST(t.fee_minor AS TEXT),CAST(t.amount_minor AS TEXT),
            '', '', '', '',CASE WHEN t.is_deleted THEN 'LEGACY_DELETED' ELSE 'LEGACY_FACT' END,'',CAST(t.id AS TEXT),'T'||printf('%020d',t.id)
        FROM investment_trades t JOIN investments p ON p.id=t.investment_id JOIN instruments i ON i.id=p.instrument_id
            LEFT JOIN cash_accounts c ON c.id=t.cash_account_id
        WHERE p.savings_account_id=? AND NOT EXISTS(SELECT 1 FROM audit_events a WHERE a.operation_id=t.operation_id)
        UNION ALL
        SELECT e.created_at_ms,CAST(e.occurred_at_ms AS TEXT),'CASH_SET',c.name,c.name,e.currency_code,'','','',CAST(e.delta_minor AS TEXT),
            e.note,'','', '',CASE WHEN e.is_deleted THEN 'LEGACY_DELETED' ELSE 'LEGACY_FACT' END,'',CAST(e.id AS TEXT),'C'||printf('%020d',e.id)
        FROM cash_entries e JOIN cash_accounts c ON c.id=e.cash_account_id
        WHERE e.savings_account_id=? AND e.source_kind='CASH_SET'
            AND NOT EXISTS(SELECT 1 FROM audit_events a WHERE a.operation_id=e.original_operation_id)
        UNION ALL
        SELECT d.created_at_ms,date(d.start_epoch_day*86400,'unixepoch'),'TERM_OPEN','',CAST(d.id AS TEXT),d.currency_code,'','','',CAST(d.principal_minor AS TEXT),
            '','','','', 'LEGACY_FACT','',CAST(d.id AS TEXT),'D'||printf('%020d',d.id)
        FROM term_deposits d WHERE d.savings_account_id=?
            AND NOT EXISTS(SELECT 1 FROM audit_events a WHERE a.operation_id=d.open_operation_id)
        ) ORDER BY operation_time DESC,stable_id DESC
    """, arrayOf(accountId.toString(), accountId.toString(), accountId.toString(), accountId.toString()))

    private fun query(sql: String, accountId: Long): Cursor =
        db.openHelper.readableDatabase.query(sql, arrayOf(accountId.toString()))

    private fun convert(snapshot: AssetSnapshot, source: Currency, value: BigDecimal): BigDecimal? {
        val base = snapshot.settings.baseCurrency ?: return null
        val rate = if (source == base) BigDecimal.ONE else snapshot.settings.rates.firstOrNull {
            it.sourceCurrency == source && it.targetCurrency == base
        }?.rate ?: BigDecimal.ONE
        return value.multiply(rate)
    }

    private fun sheetNames(snapshot: AssetSnapshot, english: Boolean): WorkbookNames {
        val used = mutableSetOf<String>()
        fun unique(raw: String): String {
            val cleaned = raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim().ifEmpty { "Sheet" }
            var candidate = cleaned.take(MAX_SHEET_NAME)
            var suffix = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                val tail = "_$suffix"
                candidate = cleaned.take(MAX_SHEET_NAME - tail.length) + tail
                suffix++
            }
            return candidate
        }
        val width = maxOf(2, snapshot.accounts.size.toString().length)
        val summary = unique(if (english) "00_Summary" else "00_汇总")
        val accounts = snapshot.accounts.mapIndexed { index, account ->
            val prefix = (index + 1).toString().padStart(width, '0')
            unique("${prefix}_${account.name}_${if (english) "Assets" else "资产"}") to
                unique("${prefix}_${account.name}_${if (english) "Records" else "记录"}")
        }
        return WorkbookNames(summary, accounts)
    }

    private fun packageWorkbook(target: File, sheets: List<SheetDefinition>) {
        ZipOutputStream(FileOutputStream(target), Charsets.UTF_8).use { zip ->
            fun textEntry(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            textEntry("[Content_Types].xml", contentTypes(sheets.size))
            textEntry("_rels/.rels", ROOT_RELS)
            textEntry("xl/workbook.xml", workbook(sheets))
            textEntry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            textEntry("xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                FileInputStream(sheet.file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun contentTypes(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        repeat(count) { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
        append("</Types>")
    }

    private fun workbook(sheets: List<SheetDefinition>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { index, sheet -> append("""<sheet name="${xml(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""") }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        repeat(count) { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
    }

    private data class WorkbookNames(val summary: String, val accounts: List<Pair<String, String>>)
    private data class SheetDefinition(val name: String, val file: File)

    private class SheetXml(file: File, private val freezeRows: Int, private val autoFilter: Boolean = false) : AutoCloseable {
        private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), StandardCharsets.UTF_8))
        private var rowNumber = 0
        private var maxColumns = 0
        private val hyperlinks = mutableListOf<Hyperlink>()

        init {
            writer.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="$freezeRows" topLeftCell="A${freezeRows + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="64" width="18" customWidth="1"/></cols><sheetData>""")
        }

        fun row(vararg cells: Cell) {
            rowNumber++
            if (rowNumber > MAX_ROWS) throw PortabilityException(PortabilityErrorCode.WORKBOOK_LIMIT)
            maxColumns = maxOf(maxColumns, cells.size)
            writer.write("<row r=\"$rowNumber\">")
            cells.forEachIndexed { index, cell ->
                val ref = columnName(index + 1) + rowNumber
                if (cell.numeric) writer.write("<c r=\"$ref\" s=\"${cell.style}\"><v>${xml(cell.value)}</v></c>")
                else writer.write("<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell.value)}</t></is></c>")
                cell.hyperlinkLocation?.let { hyperlinks += Hyperlink(ref, it, cell.value) }
            }
            writer.write("</row>")
        }

        fun blank() = row()

        override fun close() {
            writer.write("</sheetData>")
            if (autoFilter && rowNumber > 0 && maxColumns > 0) {
                writer.write("<autoFilter ref=\"A1:${columnName(maxColumns)}$rowNumber\"/>")
            }
            if (hyperlinks.isNotEmpty()) {
                writer.write("<hyperlinks>")
                hyperlinks.forEach { link ->
                    writer.write("<hyperlink ref=\"${xml(link.ref)}\" location=\"${xml(link.location)}\" display=\"${xml(link.display)}\"/>")
                }
                writer.write("</hyperlinks>")
            }
            writer.write("</worksheet>")
            writer.close()
        }
    }

    private data class Cell(
        val value: String,
        val numeric: Boolean = false,
        val style: Int = 0,
        val hyperlinkLocation: String? = null
    )
    private data class Hyperlink(val ref: String, val location: String, val display: String)

    companion object {
        private fun text(value: String) = Cell(value, style = 0)
        private fun title(value: String) = Cell(value, style = 1)
        private fun section(value: String) = Cell(value, style = 2)
        private fun header(value: String) = Cell(value, style = 3)
        private fun link(value: String, location: String) = Cell(value, style = 5, hyperlinkLocation = location)
        private fun id(value: Long) = Cell(value.toString(), style = 5)
        private fun number(value: String): Cell = numericOrText(value, 4)
        private fun decimal(value: BigDecimal?): Cell = if (value == null) text("") else numericOrText(value.stripTrailingZeros().toPlainString(), 4)
        private fun valueOrBlank(value: BigDecimal?): Cell = decimal(value)
        private fun minor(value: Long, currency: Currency): Cell = decimal(BigDecimal.valueOf(value, currency.fraction_digits))
        private fun scaled(value: String, scale: Int): Cell = value.toLongOrNull()?.let {
            decimal(BigDecimal.valueOf(it, scale))
        } ?: text(value)
        private fun currencyScaled(value: String, currency: Currency?): Cell = if (currency == null) text(value)
            else value.toLongOrNull()?.let { minor(it, currency) } ?: text(value)
        private fun numericOrText(value: String, style: Int): Cell {
            val decimal = value.toBigDecimalOrNull() ?: return Cell(value, style = 5)
            return if (decimal.precision() <= 15) Cell(decimal.toPlainString(), numeric = true, style = style)
            else Cell(value, style = 5)
        }
        private fun label(english: Boolean, zh: String, en: String) = if (english) en else zh
        private fun internalLocation(sheetName: String) = "'${sheetName.replace("'", "''")}'!A1"
        private fun instantText(milliseconds: Long): String = UTC.format(Instant.ofEpochMilli(milliseconds))
        private fun xml(value: String): String {
            if (value.length > MAX_CELL_CHARS) throw PortabilityException(PortabilityErrorCode.WORKBOOK_LIMIT)
            return buildString(value.length) {
                value.forEach { character ->
                    when {
                        character == '&' -> append("&amp;")
                        character == '<' -> append("&lt;")
                        character == '>' -> append("&gt;")
                        character == '"' -> append("&quot;")
                        character == '\'' -> append("&apos;")
                        character.code in 0..8 || character.code in 11..12 || character.code in 14..31 -> append('\uFFFD')
                        else -> append(character)
                    }
                }
            }
        }
        private fun columnName(index: Int): String {
            var value = index
            return buildString {
                while (value > 0) {
                    val remainder = (value - 1) % 26
                    insert(0, ('A'.code + remainder).toChar())
                    value = (value - 1) / 26
                }
            }
        }

        private val UTC = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val MAX_SHEET_NAME = 31
        private const val MAX_ROWS = 1_048_576
        private const val MAX_CELL_CHARS = 32_767
        private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="4"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="18"/><name val="Calibri"/></font><font><b/><sz val="13"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF3F51B5"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0"/><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
