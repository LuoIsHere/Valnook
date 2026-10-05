package dev.valnook.app.webadmin

import android.content.Context
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.webadmin.WebHttpRequest
import dev.valnook.data.webadmin.WebHttpResponse
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditLimitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.*
import dev.valnook.domain.webadmin.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID

internal class WebAdminApiRouter(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val sessions: AppSessionManager,
    private val onDataChanged: suspend (Long) -> Unit
) {
    private val reads get() = sessions.webAdminReads()

    suspend fun handle(webSessionId: String, request: WebHttpRequest): WebHttpResponse = try {
        withContext(Dispatchers.IO) { route(webSessionId, request) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: DomainException) {
        domainError(error.code)
    } catch (error: WebAdminException) {
        webError(error.error)
    } catch (_: IllegalArgumentException) {
        failure(400, "FORMAT")
    } catch (_: Exception) {
        failure(500, "INTERNAL")
    }

    private suspend fun route(sessionId: String, request: WebHttpRequest): WebHttpResponse {
        val path = request.path.removeSuffix("/").ifEmpty { "/" }
        val method = request.method
        if (method == "GET" && path == "/api/v1/session") return sessionState()
        if (method == "GET" && path == "/api/v1/accounts") return accounts()
        ACCOUNT.matchEntire(path)?.let { match ->
            if (method == "GET") return account(match.groupValues[1].toLong())
            if (method == "PUT") return saveAccount(sessionId, match.groupValues[1].toLong(), request)
        }
        BALANCE_ACCOUNT.matchEntire(path)?.let { match ->
            if (method == "DELETE") return deleteBalanceAccount(sessionId, match.groupValues[1].toLong(), request)
        }
        if (method == "POST" && path == "/api/v1/accounts") return saveAccount(sessionId, null, request)
        if (method == "GET" && path == "/api/v1/records") return records(request)
        CASH_ENTRY.matchEntire(path)?.let { if (method == "PUT")
            return editCashEntry(sessionId, it.groupValues[1].toLong(), request) }
        if (method == "GET" && path == "/api/v1/asset-types") return assetTypes()
        if (method == "POST" && path == "/api/v1/asset-types") return saveAssetType(sessionId, null, request)
        ASSET_TYPE.matchEntire(path)?.let { if (method == "PUT")
            return saveAssetType(sessionId, it.groupValues[1].toLong(), request) }
        if (method == "GET" && path == "/api/v1/instruments") return instruments()
        if (method == "POST" && path == "/api/v1/instruments") return saveInstrument(sessionId, null, request)
        INSTRUMENT.matchEntire(path)?.let { match ->
            if (method == "PUT") return saveInstrument(sessionId, match.groupValues[1].toLong(), request)
        }
        if (method == "GET" && path == "/api/v1/positions") return positions()
        if (method == "POST" && path == "/api/v1/positions") return createPosition(sessionId, request)
        POSITION.matchEntire(path)?.let { match ->
            if (method == "GET") return position(match.groupValues[1].toLong())
        }
        POSITION_TRADES.matchEntire(path)?.let {
            if (method == "GET") return positionTrades(it.groupValues[1].toLong(), request)
            if (method == "POST") return recordTrade(sessionId, it.groupValues[1].toLong(), request)
        }
        TRADE.matchEntire(path)?.let {
            if (method == "PUT") return editTrade(sessionId, it.groupValues[1].toLong(), request)
            if (method == "DELETE") return deleteTrade(sessionId, it.groupValues[1].toLong(), request)
        }
        if (method == "POST" && path == "/api/v1/deposits") return openDeposit(sessionId, request)
        DEPOSIT.matchEntire(path)?.let { if (method == "PUT")
            return editDeposit(sessionId, it.groupValues[1].toLong(), request) }
        DEPOSIT_CLOSE.matchEntire(path)?.let { if (method == "POST")
            return closeDeposit(sessionId, it.groupValues[1].toLong(), request) }
        OPERATION.matchEntire(path)?.let { if (method == "GET")
            return operationResult(sessionId, it.groupValues[1]) }
        if (method == "GET" && path == "/api/v1/statistics/summary") return statisticsSummary()
        if (method == "GET" && path == "/api/v1/statistics/history") return statisticsHistory(request)
        if (method == "GET" && path == "/api/v1/statistics/distribution") return distributions()
        return failure(404, "NOT_FOUND")
    }

    private suspend fun sessionState(): WebHttpResponse {
        val snapshot = reads.snapshot()
        return success(buildJsonObject {
            put("status", "active")
            put("apiVersion", WEB_API_VERSION)
            put("assetVersion", WEB_ASSET_VERSION)
            put("dataGeneration", reads.generation())
            put("dataMode", sessions.session.value.mode.name)
            put("language", snapshot.settings.language.name)
            put("gainLossColors", snapshot.settings.gainLossColors.name)
            put("baseCurrency", snapshot.settings.baseCurrency?.code)
        })
    }

    private suspend fun accounts(): WebHttpResponse {
        val snapshot = reads.snapshot()
        val overview = AssetValuation.calculate(snapshot)
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("baseCurrency", snapshot.settings.baseCurrency?.code)
            put("items", buildJsonArray { overview.accounts.forEach { row -> add(buildJsonObject {
                put("id", row.account.id); put("revision", row.account.revision)
                put("name", row.account.name); put("note", row.account.note)
                put("cash", decimal(row.cash.amount)); put("creditBalance", decimal(row.creditBalance.amount))
                put("deposits", decimal(row.depositValue.amount))
                put("investments", decimal(row.investmentValue.amount)); put("total", decimal(row.total.amount))
                put("complete", row.total.complete)
            }) } })
        })
    }

    private suspend fun account(id: Long): WebHttpResponse {
        val snapshot = reads.snapshot()
        val account = snapshot.accounts.firstOrNull { it.id == id } ?: return failure(404, "NOT_FOUND")
        val deposits = reads.deposits().filter { it.account_id == id }
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("account", buildJsonObject {
                put("id", account.id); put("revision", account.revision); put("name", account.name); put("note", account.note)
            })
            put("cash", buildJsonArray { snapshot.cash.filter { it.account_id == id }.forEach { add(cash(it, snapshot)) } })
            put("creditSourceCandidates", buildJsonArray {
                val names = snapshot.accounts.associate { it.id to it.name }
                snapshot.cash.filter { it.account_id == id && it.type == BalanceAccountType.CREDIT &&
                    it.creditProfile?.limitSourceAccountId == null }
                    .forEach { value -> add(buildJsonObject {
                        put("id", value.id); put("name", value.name); put("currencyCode", value.currency.code)
                        put("accountId", value.account_id); put("accountName", names[value.account_id].orEmpty())
                    }) }
            })
            put("deposits", buildJsonArray { deposits.forEach { add(deposit(it)) } })
            put("positions", buildJsonArray { snapshot.positions.filter { it.account_id == id }.forEach { add(position(it)) } })
        })
    }

    private suspend fun saveAccount(sessionId: String, id: Long?, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val changes = body["cashChanges"]?.jsonArray?.map { value ->
            val row = value.jsonObject
            val currency = Currency.of(row.text("currencyCode"))
            val type = row.optionalText("type")?.let(BalanceAccountType::valueOf) ?: BalanceAccountType.SAVINGS
            val credit = if (type == BalanceAccountType.CREDIT) {
                val value = row["credit"]?.jsonObject ?: throw IllegalArgumentException()
                val source = value.optionalLong("limitSourceAccountId")
                val due = value["dueRule"]?.jsonObject ?: throw IllegalArgumentException()
                CreditAccountInput(
                    if (source == null) DecimalRules.parse_minor(value.text("creditLimit"), currency, positive = true) else null,
                    value.long("statementDay").toInt(),
                    when (due.text("type")) {
                        "AFTER_STATEMENT_DAYS" -> CreditDueRule.AfterStatementDays(due.long("value").toInt())
                        "FIXED_DAY_OF_MONTH" -> CreditDueRule.FixedDayOfMonth(due.long("value").toInt())
                        else -> throw IllegalArgumentException()
                    }, source)
            } else null
            CashBalanceChange(currency.code, DecimalRules.parse_signed_minor(row.text("balance"), currency),
                row.optionalLong("expectedRevision"), row.optionalLong("cashAccountId"),
                row.optionalText("name") ?: currency.code, row.optionalText("note").orEmpty(), type, credit)
        }.orEmpty()
        return save(sessionId, body, SaveAccount(body.operationId(), id, body.optionalLong("expectedRevision"),
            body.text("name"), body.optionalText("note").orEmpty(), changes))
    }

    private suspend fun deleteBalanceAccount(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        return save(sessionId, body, DeleteBalanceAccount(body.operationId(), body.long("accountId"), id,
            body.long("expectedRevision")))
    }

    private suspend fun records(request: WebHttpRequest): WebHttpResponse {
        val query = query(request.query)
        val cursor = query["cursor"]?.let { value ->
            val split = value.split('|', limit = 2)
            if (split.size != 2) throw IllegalArgumentException()
            WebRecordCursor(split[0].toLong(), split[1])
        }
        val page = reads.records(WebRecordFilter(
            fromMs = query["from"]?.toLongOrNull(),
            toMs = query["to"]?.toLongOrNull(),
            accountId = query["accountId"]?.toLongOrNull(),
            kind = query["type"]?.uppercase()?.let(WebRecordKind::valueOf),
            query = query["query"].orEmpty(),
            cursor = cursor,
            pageSize = query["pageSize"]?.toIntOrNull() ?: 50))
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("items", buildJsonArray { page.items.forEach { add(record(it)) } })
            put("nextCursor", page.nextCursor?.let { "${it.businessAtMs}|${it.stableKey}" })
        })
    }

    private suspend fun editCashEntry(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val currency = Currency.of(body.text("currencyCode"))
        return save(sessionId, body, EditCashEntry(body.operationId(), id, body.long("expectedRevision"),
            DecimalRules.parse_signed_minor(body.text("amount"), currency), body.long("occurredAtMs"),
            body.optionalText("note").orEmpty()))
    }

    private suspend fun assetTypes(): WebHttpResponse = success(buildJsonObject {
        put("dataGeneration", reads.generation())
        put("items", buildJsonArray { reads.assetTypes().forEach { add(buildJsonObject {
            put("id", it.id); put("name", it.name)
        }) } })
    })

    private suspend fun saveAssetType(sessionId: String, id: Long?, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        return save(sessionId, body, SaveAssetType(body.operationId(), id, body.text("name")))
    }

    private suspend fun instruments(): WebHttpResponse {
        val snapshot = reads.snapshot()
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("items", buildJsonArray { snapshot.instruments.forEach { add(instrument(it)) } })
        })
    }

    private suspend fun saveInstrument(sessionId: String, id: Long?, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val command = SaveInstrument(body.operationId(), id, body.optionalLong("expectedRevision"),
            body.text("name"), body.text("symbol"), body.long("typeId"), body.text("currencyCode"),
            DecimalRules.parse_units(body.text("currentPrice"), 5), body.optionalBoolean("currencyPriceConfirmed") ?: false)
        return save(sessionId, body, command)
    }

    private suspend fun positions(): WebHttpResponse {
        val snapshot = reads.snapshot()
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("items", buildJsonArray { snapshot.positions.forEach { add(position(it)) } })
        })
    }

    private suspend fun position(id: Long): WebHttpResponse {
        val value = reads.snapshot().positions.firstOrNull { it.id == id } ?: return failure(404, "NOT_FOUND")
        val trades = sessions.webAdminReads().records(WebRecordFilter(kind = WebRecordKind.TRADE,
            accountId = value.account_id, childId = id, pageSize = 100)).items
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("position", position(value))
            put("trades", buildJsonArray { trades.forEach { add(record(it)) } })
        })
    }

    private suspend fun positionTrades(id: Long, request: WebHttpRequest): WebHttpResponse {
        val value = reads.snapshot().positions.firstOrNull { it.id == id } ?: return failure(404, "NOT_FOUND")
        val values = query(request.query)
        val cursor = values["cursor"]?.let { encoded ->
            val split = encoded.split('|', limit = 2)
            if (split.size != 2) throw IllegalArgumentException()
            WebRecordCursor(split[0].toLong(), split[1])
        }
        val page = reads.records(WebRecordFilter(kind = WebRecordKind.TRADE, accountId = value.account_id,
            childId = id, cursor = cursor, pageSize = values["pageSize"]?.toIntOrNull() ?: 50))
        return success(buildJsonObject {
            put("dataGeneration", reads.generation())
            put("items", buildJsonArray { page.items.forEach { add(record(it)) } })
            put("nextCursor", page.nextCursor?.let { "${it.businessAtMs}|${it.stableKey}" })
        })
    }

    private suspend fun createPosition(sessionId: String, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        return save(sessionId, body, CreateInvestmentPosition(body.operationId(), body.long("accountId"),
            body.long("instrumentId")))
    }

    private suspend fun recordTrade(sessionId: String, positionId: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val currency = Currency.of(body.text("currencyCode"))
        return save(sessionId, body, RecordInvestmentTrade(body.operationId(), positionId,
            Direction.valueOf(body.text("direction").uppercase()), DecimalRules.parse_e8(body.text("quantity"), true),
            DecimalRules.parse_e8(body.text("executionPrice"), true), body.long("occurredAtMs"),
            body.optionalBoolean("cashLinked") ?: false, body.optionalLong("cashAccountId"),
            DecimalRules.parse_minor(body.optionalText("fee") ?: "0", currency)))
    }

    private suspend fun editTrade(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val currency = Currency.of(body.text("currencyCode"))
        return save(sessionId, body, EditInvestmentTrade(body.operationId(), id, body.long("expectedRevision"),
            Direction.valueOf(body.text("direction").uppercase()), DecimalRules.parse_e8(body.text("quantity"), true),
            DecimalRules.parse_e8(body.text("executionPrice"), true), body.long("occurredAtMs"),
            body.optionalBoolean("cashLinked") ?: false, body.optionalLong("cashAccountId"),
            DecimalRules.parse_minor(body.optionalText("fee") ?: "0", currency)))
    }

    private suspend fun deleteTrade(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        return save(sessionId, body, DeleteInvestmentTrade(body.operationId(), id, body.long("expectedRevision")))
    }

    private suspend fun openDeposit(sessionId: String, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val currency = Currency.of(body.text("currencyCode"))
        return save(sessionId, body, OpenTermDeposit(body.operationId(), body.long("accountId"), currency.code,
            DecimalRules.parse_minor(body.text("principal"), currency, true),
            DecimalRules.parse_units(body.text("annualRatePercent"), 8), body.long("startEpochDay"),
            body.long("endEpochDay"), body.optionalBoolean("cashLinked") ?: false,
            body.optionalLong("cashAccountId")))
    }

    private suspend fun editDeposit(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        val currency = Currency.of(body.text("currencyCode"))
        return save(sessionId, body, EditTermDeposit(body.operationId(), id, body.long("expectedRevision"),
            DecimalRules.parse_minor(body.text("principal"), currency, true),
            DecimalRules.parse_units(body.text("annualRatePercent"), 8), body.long("startEpochDay"),
            body.long("endEpochDay"), body.optionalBoolean("openCashLinked") ?: false,
            body.optionalBoolean("closeCashLinked"), body.optionalLong("openCashAccountId"),
            body.optionalLong("closeCashAccountId")))
    }

    private suspend fun closeDeposit(sessionId: String, id: Long, request: WebHttpRequest): WebHttpResponse {
        val body = body(request)
        return save(sessionId, body, CloseTermDeposit(body.operationId(), id,
            body.optionalBoolean("cashLinked") ?: false, body.optionalLong("cashAccountId")))
    }

    private suspend fun operationResult(sessionId: String, operationId: String): WebHttpResponse {
        UUID.fromString(operationId)
        val result = sessions.webOperationResult(sessionId, operationId) ?: return failure(404, "NOT_FOUND")
        return success(buildJsonObject { put("status", "saved"); put("kind", result.kind); put("id", result.id)
            put("operationId", operationId); put("dataGeneration", reads.generation()) })
    }

    private suspend fun statisticsSummary(): WebHttpResponse {
        val value = reads.currentStatistics()
        return success(buildJsonObject {
            put("dataGeneration", reads.generation()); put("currency", value.currency?.code)
            put("totalAssets", value.totalAssets?.let(::decimal)); put("availableCash", value.availableCash?.let(::decimal))
            put("investmentValue", value.investmentValue?.let(::decimal))
            put("monthlyChange", value.monthlyChange.value?.let(::decimal))
        })
    }

    private suspend fun statisticsHistory(request: WebHttpRequest): WebHttpResponse {
        val values = query(request.query)
        val granularity = StatisticsGranularity.valueOf(values["granularity"]?.uppercase() ?: "DAILY")
        val today = LocalDate.now()
        val period = StatisticsPeriod(
            granularity = granularity,
            year = values["year"]?.toIntOrNull() ?: today.year,
            month = if (granularity == StatisticsGranularity.DAILY) {
                values["month"]?.toIntOrNull() ?: today.monthValue
            } else null
        )
        val metric = StatisticsMetric.valueOf(values["metric"]?.uppercase() ?: "TOTAL_ASSETS")
        val series = reads.statisticsSeries(StatisticsRequest(metric, period))
        return success(buildJsonObject {
            put("dataGeneration", reads.generation()); put("currency", series.currency?.code)
            put("items", buildJsonArray { series.points.forEach { point -> add(buildJsonObject {
                put("date", point.date.toString()); put("value", point.value?.let(::decimal))
                put("reliable", point.reliable); put("future", point.future)
            }) } })
        })
    }

    private suspend fun distributions(): WebHttpResponse {
        val snapshot = reads.snapshot()
        val overview = AssetValuation.calculate(snapshot)
        val rates = snapshot.settings.rates.associate { it.sourceCurrency.code to it.rate }
        fun convert(code: String, amount: BigDecimal): BigDecimal =
            if (code == snapshot.settings.baseCurrency?.code) amount else amount.multiply(rates[code] ?: BigDecimal.ONE)
        val types = snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.type_name }.mapValues { (_, rows) ->
            rows.fold(BigDecimal.ZERO) { total, row -> total + convert(row.currency.code, AssetValuation.marketValue(row)) }
        }
        val currencies = mutableMapOf<String, BigDecimal>()
        snapshot.cash.forEach { currencies[it.currency.code] = currencies.getOrDefault(it.currency.code, BigDecimal.ZERO) +
            BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits) }
        snapshot.deposits.filterNot { it.closed }.forEach { currencies[it.currency.code] =
            currencies.getOrDefault(it.currency.code, BigDecimal.ZERO) + BigDecimal.valueOf(it.principal_minor, it.currency.fraction_digits) }
        snapshot.positions.filter { it.holding_quantity_e8 > 0 }.forEach { currencies[it.currency.code] =
            currencies.getOrDefault(it.currency.code, BigDecimal.ZERO) + AssetValuation.marketValue(it) }
        fun map(values: Map<String, BigDecimal>, convertValues: Boolean = false) = buildJsonArray {
            values.toSortedMap().forEach { (label, amount) -> add(buildJsonObject { put("label", label)
                put("value", decimal(if (convertValues) convert(label, amount) else amount)) }) }
        }
        return success(buildJsonObject {
            put("dataGeneration", reads.generation()); put("currency", snapshot.settings.baseCurrency?.code)
            put("accounts", buildJsonArray { overview.accounts.forEach { add(buildJsonObject {
                put("label", it.account.name); put("value", decimal(it.total.amount))
            }) } })
            put("assetTypes", map(types)); put("currencies", map(currencies, true))
        })
    }

    private suspend fun save(sessionId: String, body: JsonObject, command: FinancialCommand): WebHttpResponse {
        val receipt = sessions.executeWebCommand(sessionId, body.long("dataGeneration"), command)
        onDataChanged(receipt.dataGeneration)
        return success(buildJsonObject {
            put("status", "saved"); put("revision", body.optionalLong("expectedRevision")?.plus(1))
            put("kind", receipt.result.kind); put("id", receipt.result.id)
            put("operationId", command.operation_id); put("dataGeneration", receipt.dataGeneration)
        })
    }

    private fun cash(value: CashAccount, snapshot: AssetSnapshot) = buildJsonObject {
        put("id", value.id); put("accountId", value.account_id); put("revision", value.revision)
        put("name", value.name); put("note", value.note); put("currencyCode", value.currency.code)
        put("balance", DecimalRules.format_units(value.balance_minor, value.currency.fraction_digits))
        put("currencyLocked", value.currencyLocked)
        put("type", value.type.name)
        if (value.creditProfile == null) put("credit", JsonNull) else {
            val profile = requireNotNull(value.creditProfile)
            val summary = runCatching { CreditLimitCalculator.calculate(value.id, snapshot.cash) }.getOrNull()
            val billing = CreditBillingCalendar.calculate(LocalDate.now(), profile.statementDay, profile.dueRule)
            put("credit", buildJsonObject {
                put("limitSourceAccountId", profile.limitSourceAccountId)
                put("creditLimit", profile.creditLimitMinor?.let {
                    DecimalRules.format_units(it, value.currency.fraction_digits) })
                put("statementDay", profile.statementDay)
                put("dueRule", buildJsonObject {
                    put("type", if (profile.dueRule is CreditDueRule.FixedDayOfMonth)
                        "FIXED_DAY_OF_MONTH" else "AFTER_STATEMENT_DAYS")
                    put("value", profile.dueRule.value)
                })
                put("used", summary?.usedLimitMinor?.movePointLeft(value.currency.fraction_digits)?.let(::decimal))
                put("available", summary?.availableLimitMinor?.movePointLeft(value.currency.fraction_digits)?.let(::decimal))
                put("totalLimit", summary?.totalLimitMinor?.movePointLeft(value.currency.fraction_digits)?.let(::decimal))
                put("overLimit", summary?.overLimitMinor?.movePointLeft(value.currency.fraction_digits)?.let(::decimal))
                put("focus", billing.focus.name); put("focusDate", billing.focusDate.toString())
                put("daysRemaining", billing.daysRemaining)
            })
        }
    }

    private fun deposit(value: TermDeposit) = buildJsonObject {
        put("id", value.id); put("accountId", value.account_id); put("revision", value.revision)
        put("currencyCode", value.currency.code)
        put("principal", DecimalRules.format_units(value.principal_minor, value.currency.fraction_digits))
        put("annualRatePercent", DecimalRules.format_units(value.annual_rate_percent_e8, 8))
        put("startEpochDay", value.start_epoch_day); put("endEpochDay", value.end_epoch_day)
        put("startDate", LocalDate.ofEpochDay(value.start_epoch_day).toString())
        put("endDate", LocalDate.ofEpochDay(value.end_epoch_day).toString())
        put("expectedInterest", DecimalRules.format_units(value.expected_interest_minor, value.currency.fraction_digits))
        put("closed", value.closed); put("openCashLinked", value.open_cash_linked)
        put("closeCashLinked", value.close_cash_linked); put("openCashAccountId", value.openCashAccountId)
        put("closeCashAccountId", value.closeCashAccountId)
    }

    private fun instrument(value: Instrument) = buildJsonObject {
        put("id", value.id); put("revision", value.revision); put("name", value.name); put("symbol", value.symbol)
        put("typeId", value.typeId); put("typeName", value.typeName); put("currencyCode", value.currency.code)
        put("currentPrice", DecimalRules.format_units(value.currentPriceE5, 5)); put("priceUpdatedAtMs", value.priceUpdatedAtMs)
        put("currencyLocked", value.currencyLocked); put("symbolLocked", value.symbolLocked)
    }

    private fun position(value: Investment) = buildJsonObject {
        val profit = InvestmentProfitCalculator.fromReadModel(value)
        put("id", value.id); put("revision", value.revision); put("accountId", value.account_id)
        put("instrumentId", value.instrumentId); put("name", value.name); put("symbol", value.symbol)
        put("typeName", value.type_name); put("currencyCode", value.currency.code)
        put("quantity", DecimalRules.format_e8(value.holding_quantity_e8))
        put("currentPrice", DecimalRules.format_e8(value.current_price_e8))
        put("marketValue", decimal(AssetValuation.marketValue(value)))
        put("averageCost", profit.average_cost?.let(::decimal)); put("remainingCost", profit.remainingCost?.let(::decimal))
        put("realized", profit.realized?.let(::decimal)); put("unrealized", profit.unrealized?.let(::decimal))
        put("unrealizedPercent", profit.unrealizedPercent?.let(::decimal)); put("chronologyValid", profit.chronology_valid)
        put("lastActivityAtMs", value.last_activity_at_ms)
    }

    private fun record(value: WebRecord) = buildJsonObject {
        put("kind", value.kind.name); put("id", value.id); put("revision", value.revision)
        put("businessAtMs", value.businessAtMs); put("accountId", value.accountId); put("accountName", value.accountName)
        put("childId", value.childId); put("childName", value.childName); put("action", value.action)
        put("objectName", value.objectName); put("amount", value.amount); put("currencyCode", value.currencyCode)
        put("quantity", value.quantity); put("unitPrice", value.unitPrice); put("fee", value.fee)
        put("cashLinked", value.cashLinked); put("linkedCashAccountId", value.linkedCashAccountId)
        put("sourceId", value.sourceId); put("sourceParentId", value.sourceParentId)
        put("note", value.note); put("updatedAtMs", value.updatedAtMs)
    }

    private fun body(request: WebHttpRequest): JsonObject {
        val value = Json.parseToJsonElement(request.body.toString(Charsets.UTF_8))
        if (depth(value) > 16) throw IllegalArgumentException()
        return value.jsonObject
    }

    private fun depth(value: JsonElement): Int = when (value) {
        is JsonArray -> 1 + (value.maxOfOrNull(::depth) ?: 0)
        is JsonObject -> 1 + (value.values.maxOfOrNull(::depth) ?: 0)
        else -> 1
    }

    private fun query(value: String): Map<String, String> {
        if (value.isBlank()) return emptyMap()
        return value.split('&').associate { part ->
            val pieces = part.split('=', limit = 2)
            URLDecoder.decode(pieces[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }
    }

    private fun JsonObject.operationId(): String = text("operationId").also(UUID::fromString)
    private fun JsonObject.text(name: String): String = this[name]?.jsonPrimitive?.content
        ?.takeIf { it.length <= 500 } ?: throw IllegalArgumentException()
    private fun JsonObject.optionalText(name: String): String? = this[name]?.let {
        if (it is JsonNull) null else it.jsonPrimitive.content.takeIf { value -> value.length <= 500 }
            ?: throw IllegalArgumentException()
    }
    private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException()
    private fun JsonObject.optionalLong(name: String): Long? = this[name]?.let {
        if (it is JsonNull) null else it.jsonPrimitive.longOrNull ?: throw IllegalArgumentException()
    }
    private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.let {
        if (it is JsonNull) null else it.jsonPrimitive.booleanOrNull ?: throw IllegalArgumentException()
    }

    private fun success(value: JsonObject) = WebHttpResponse(200, "application/json; charset=utf-8", value.toString())

    private fun failure(status: Int, code: String, refresh: Boolean = false) = WebHttpResponse(status,
        "application/json; charset=utf-8", buildJsonObject { put("error", buildJsonObject {
            put("code", code); put("messageKey", code.lowercase()); put("refreshRequired", refresh)
        }) }.toString())

    private fun domainError(code: ErrorCode): WebHttpResponse = when (code) {
        ErrorCode.SESSION_EXPIRED -> failure(401, code.name, true)
        ErrorCode.NOT_FOUND -> failure(404, code.name)
        ErrorCode.STALE_RECORD, ErrorCode.STALE_BALANCE, ErrorCode.OPERATION_CONFLICT,
        ErrorCode.HISTORY_CONFLICT -> failure(409, code.name, true)
        ErrorCode.WEB_ADMIN_ACTIVE, ErrorCode.MAINTENANCE_ACTIVE -> failure(423, code.name)
        ErrorCode.CURRENCY_LOCKED, ErrorCode.SYMBOL_LOCKED, ErrorCode.INSUFFICIENT_CASH,
        ErrorCode.INSUFFICIENT_HOLDING, ErrorCode.CASH_ACCOUNT_IN_USE, ErrorCode.ALREADY_CLOSED,
        ErrorCode.CREDIT_SOURCE_INVALID, ErrorCode.CREDIT_SOURCE_CURRENCY, ErrorCode.CREDIT_SOURCE_PARENT,
        ErrorCode.CREDIT_SOURCE_CHAIN,
        ErrorCode.CREDIT_SOURCE_CYCLE, ErrorCode.CREDIT_LIMIT_IN_USE, ErrorCode.BALANCE_ACCOUNT_IN_USE ->
            failure(409, code.name)
        else -> failure(400, code.name)
    }

    private fun webError(error: WebAdminError): WebHttpResponse = when (error) {
        WebAdminError.SESSION_BUSY -> failure(409, error.name)
        WebAdminError.MAINTENANCE_ACTIVE -> failure(423, error.name)
        else -> failure(500, error.name)
    }

    private fun decimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private companion object {
        val ACCOUNT = Regex("/api/v1/accounts/([0-9]+)")
        val BALANCE_ACCOUNT = Regex("/api/v1/balance-accounts/([0-9]+)")
        val CASH_ENTRY = Regex("/api/v1/cash-entries/([0-9]+)")
        val ASSET_TYPE = Regex("/api/v1/asset-types/([0-9]+)")
        val INSTRUMENT = Regex("/api/v1/instruments/([0-9]+)")
        val POSITION = Regex("/api/v1/positions/([0-9]+)")
        val POSITION_TRADES = Regex("/api/v1/positions/([0-9]+)/trades")
        val TRADE = Regex("/api/v1/trades/([0-9]+)")
        val DEPOSIT = Regex("/api/v1/deposits/([0-9]+)")
        val DEPOSIT_CLOSE = Regex("/api/v1/deposits/([0-9]+)/close")
        val OPERATION = Regex("/api/v1/operations/([0-9a-fA-F-]{36})")
    }
}
