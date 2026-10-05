package dev.valnook.app.di

import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.CloseTermDeposit
import dev.valnook.domain.repository.CreateInvestmentPosition
import dev.valnook.domain.repository.EditCashEntry
import dev.valnook.domain.repository.EditInvestmentTrade
import dev.valnook.domain.repository.EditTermDeposit
import dev.valnook.domain.repository.OpenTermDeposit
import dev.valnook.domain.repository.RecordInvestmentTrade
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveAssetType
import dev.valnook.domain.repository.SaveInstrument
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveGainLossColors
import dev.valnook.domain.repository.SaveLanguage
import dev.valnook.domain.repository.SetCashBalance
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal class DemoDataSeeder(private val graph: AppGraph, private val clock: Clock) {
    private data class DemoStock(val name: String, val symbol: String, val market: String,
        val currency: String, val price: BigDecimal)
    private data class DemoCash(val currency: String, val balanceMinor: Long, val name: String)
    private data class DemoAccount(val name: String, val note: String, val cash: List<DemoCash>)

    suspend fun seed(displayPreferences: AppSettings) {
        var stored = graph.settingsWriter.applyChange(SaveFinancialSettings(0,Currency.of("CNY"),listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")),
            FxRate(Currency.of("HKD"), Currency.of("CNY"), BigDecimal("0.92"))
        )))
        if(stored.language!=displayPreferences.language) stored=graph.settingsWriter.applyChange(
            SaveLanguage(stored.revision,displayPreferences.language))
        if(stored.gainLossColors!=displayPreferences.gainLossColors) graph.settingsWriter.applyChange(
            SaveGainLossColors(stored.revision,displayPreferences.gainLossColors))

        val bankSpecs = listOf(
            DemoAccount("招商银行", "银行账户 · 工资与日常支出", listOf(
                DemoCash("CNY", 500_000L, "招商银行活期"), DemoCash("USD", 20_000L, "美元储蓄"))),
            DemoAccount("中国工商银行", "银行账户 · 家庭日常开支", listOf(
                DemoCash("CNY", 400_000L, "工商银行活期"))),
            DemoAccount("中国银行", "银行账户 · 跨境资金", listOf(
                DemoCash("CNY", 300_000L, "中国银行活期"), DemoCash("HKD", 100_000L, "港币储蓄"))),
            DemoAccount("中国建设银行", "银行账户 · 家庭储备", listOf(
                DemoCash("CNY", 400_000L, "建设银行活期"))),
            DemoAccount("交通银行", "银行账户 · 交通与出行", listOf(
                DemoCash("CNY", 200_000L, "交通银行活期"))),
            DemoAccount("汇丰银行", "银行账户 · 外币往来", listOf(
                DemoCash("USD", 25_000L, "美元往来账户"), DemoCash("HKD", 250_000L, "港币往来账户")))
        )
        val brokerSpecs = listOf("中信证券", "华泰证券", "广发证券", "中金财富", "富途证券", "盈透证券")
            .mapIndexed { index, name ->
                DemoAccount(name, "证券账户 · ${listOf("A 股与长期持有", "稳健组合", "价值投资", "多市场组合", "港美股交易", "海外长期持有")[index]}", listOf(
                    DemoCash("CNY", listOf(240_000L, 60_000L, 90_000L, 902_000L, 70_000L, 259_000L)[index], "人民币交易资金"),
                    DemoCash("USD", listOf(180_000L, 35_000L, 72_600L, 114_200L, 115_000L, 30_000L)[index], "美元交易资金"),
                    DemoCash("HKD", listOf(1_000_000L, 1_810_000L, 475_000L, 1_175_000L, 686_000L, 80_000L)[index], "港币交易资金")
                ))
            }
        suspend fun createAccounts(specs: List<DemoAccount>) = specs.map { spec ->
            graph.commands.execute(SaveAccount(id(), null, null, spec.name, spec.note, spec.cash.map { cash ->
                CashBalanceChange(cash.currency, cash.balanceMinor, null, name = cash.name)
            })).id
        }
        val bankAccounts = createAccounts(bankSpecs)
        val brokerAccounts = createAccounts(brokerSpecs)
        seedCreditAccounts(bankAccounts)
        val tradeCashByAccountCurrency = graph.overview.snapshot().cash
            .filter { it.type == BalanceAccountType.SAVINGS }
            .associateBy { it.account_id to it.currency.code }

        val typeNames = listOf("A股股票", "港股股票", "美股股票")
        val types = typeNames.map { graph.commands.execute(SaveAssetType(id(), null, it)).id }
        val stocks = listOf(
            DemoStock("贵州茅台", "600519.SH", "A股股票", "CNY", BigDecimal("1468.20")),
            DemoStock("宁德时代", "300750.SZ", "A股股票", "CNY", BigDecimal("238.50")),
            DemoStock("中国平安", "601318.SH", "A股股票", "CNY", BigDecimal("57.40")),
            DemoStock("招商银行", "600036.SH", "A股股票", "CNY", BigDecimal("42.15")),
            DemoStock("比亚迪", "002594.SZ", "A股股票", "CNY", BigDecimal("312.60")),
            DemoStock("美的集团", "000333.SZ", "A股股票", "CNY", BigDecimal("76.35")),
            DemoStock("五粮液", "000858.SZ", "A股股票", "CNY", BigDecimal("129.80")),
            DemoStock("隆基绿能", "601012.SH", "A股股票", "CNY", BigDecimal("19.72")),
            DemoStock("中信证券", "600030.SH", "A股股票", "CNY", BigDecimal("28.44")),
            DemoStock("长江电力", "600900.SH", "A股股票", "CNY", BigDecimal("29.16")),
            DemoStock("紫金矿业", "601899.SH", "A股股票", "CNY", BigDecimal("19.38")),
            DemoStock("中国移动", "600941.SH", "A股股票", "CNY", BigDecimal("112.30")),
            DemoStock("海天味业", "603288.SH", "A股股票", "CNY", BigDecimal("45.62")),
            DemoStock("迈瑞医疗", "300760.SZ", "A股股票", "CNY", BigDecimal("267.80")),
            DemoStock("恒瑞医药", "600276.SH", "A股股票", "CNY", BigDecimal("51.25")),
            DemoStock("万华化学", "600309.SH", "A股股票", "CNY", BigDecimal("83.90")),
            DemoStock("立讯精密", "002475.SZ", "A股股票", "CNY", BigDecimal("43.18")),
            DemoStock("京东方A", "000725.SZ", "A股股票", "CNY", BigDecimal("4.26")),
            DemoStock("中国神华", "601088.SH", "A股股票", "CNY", BigDecimal("42.70")),
            DemoStock("三一重工", "600031.SH", "A股股票", "CNY", BigDecimal("18.64")),
            DemoStock("腾讯控股", "0700.HK", "港股股票", "HKD", BigDecimal("598.00")),
            DemoStock("阿里巴巴-W", "9988.HK", "港股股票", "HKD", BigDecimal("158.40")),
            DemoStock("美团-W", "3690.HK", "港股股票", "HKD", BigDecimal("112.70")),
            DemoStock("小米集团-W", "1810.HK", "港股股票", "HKD", BigDecimal("52.35")),
            DemoStock("汇丰控股", "0005.HK", "港股股票", "HKD", BigDecimal("104.20")),
            DemoStock("友邦保险", "1299.HK", "港股股票", "HKD", BigDecimal("78.55")),
            DemoStock("香港交易所", "0388.HK", "港股股票", "HKD", BigDecimal("432.60")),
            DemoStock("中国移动", "0941.HK", "港股股票", "HKD", BigDecimal("86.80")),
            DemoStock("比亚迪股份", "1211.HK", "港股股票", "HKD", BigDecimal("318.40")),
            DemoStock("中国海洋石油", "0883.HK", "港股股票", "HKD", BigDecimal("20.82")),
            DemoStock("中国平安", "2318.HK", "港股股票", "HKD", BigDecimal("58.65")),
            DemoStock("工商银行", "1398.HK", "港股股票", "HKD", BigDecimal("6.31")),
            DemoStock("建设银行", "0939.HK", "港股股票", "HKD", BigDecimal("7.82")),
            DemoStock("理想汽车-W", "2015.HK", "港股股票", "HKD", BigDecimal("98.30")),
            DemoStock("网易-S", "9999.HK", "港股股票", "HKD", BigDecimal("219.40")),
            DemoStock("京东集团-SW", "9618.HK", "港股股票", "HKD", BigDecimal("132.60")),
            DemoStock("快手-W", "1024.HK", "港股股票", "HKD", BigDecimal("74.15")),
            DemoStock("中芯国际", "0981.HK", "港股股票", "HKD", BigDecimal("68.90")),
            DemoStock("药明生物", "2269.HK", "港股股票", "HKD", BigDecimal("31.75")),
            DemoStock("长和", "0001.HK", "港股股票", "HKD", BigDecimal("53.20")),
            DemoStock("Apple", "AAPL", "美股股票", "USD", BigDecimal("258.40")),
            DemoStock("Microsoft", "MSFT", "美股股票", "USD", BigDecimal("518.70")),
            DemoStock("NVIDIA", "NVDA", "美股股票", "USD", BigDecimal("188.25")),
            DemoStock("Alphabet", "GOOGL", "美股股票", "USD", BigDecimal("326.10")),
            DemoStock("Amazon", "AMZN", "美股股票", "USD", BigDecimal("242.80")),
            DemoStock("Meta Platforms", "META", "美股股票", "USD", BigDecimal("742.30")),
            DemoStock("Tesla", "TSLA", "美股股票", "USD", BigDecimal("451.20")),
            DemoStock("Berkshire Hathaway", "BRK.B", "美股股票", "USD", BigDecimal("521.40")),
            DemoStock("JPMorgan Chase", "JPM", "美股股票", "USD", BigDecimal("318.60")),
            DemoStock("Visa", "V", "美股股票", "USD", BigDecimal("378.90")),
            DemoStock("Eli Lilly", "LLY", "美股股票", "USD", BigDecimal("1098.20")),
            DemoStock("Walmart", "WMT", "美股股票", "USD", BigDecimal("129.75")),
            DemoStock("Exxon Mobil", "XOM", "美股股票", "USD", BigDecimal("118.30")),
            DemoStock("Mastercard", "MA", "美股股票", "USD", BigDecimal("612.40")),
            DemoStock("Broadcom", "AVGO", "美股股票", "USD", BigDecimal("356.70")),
            DemoStock("Netflix", "NFLX", "美股股票", "USD", BigDecimal("1242.50")),
            DemoStock("Costco", "COST", "美股股票", "USD", BigDecimal("1018.60")),
            DemoStock("Johnson & Johnson", "JNJ", "美股股票", "USD", BigDecimal("204.35")),
            DemoStock("Procter & Gamble", "PG", "美股股票", "USD", BigDecimal("171.80")),
            DemoStock("AMD", "AMD", "美股股票", "USD", BigDecimal("268.45"))
        )
        val typeByName = typeNames.zip(types).toMap()
        val instruments = stocks.map { stock ->
            graph.commands.execute(SaveInstrument(id(), null, null, stock.name, stock.symbol,
                requireNotNull(typeByName[stock.market]), stock.currency,
                R.parse_units(stock.price.toPlainString(), 5))).id
        }
        val stockById = instruments.zip(stocks).toMap()

        val today = LocalDate.now(clock)
        val historyStart = LocalDate.of(today.year - 8, 1, 5)
        val pairs = buildList {
            brokerAccounts.forEachIndexed { accountIndex, accountId ->
                repeat(4) { offset -> add(accountId to instruments[(accountIndex * 7 + offset * 13) % instruments.size]) }
            }
        }
        pairs.forEachIndexed { positionIndex, (accountId, instrumentId) ->
            val stock = requireNotNull(stockById[instrumentId])
            val positionStart = historyStart.plusDays(((positionIndex * 19) % 500).toLong())
            val openingFactor = BigDecimal("0.74").add(BigDecimal.valueOf(((positionIndex * 7) % 19).toLong(), 2))
            val openingPrice = R.parse_e8(stock.price.multiply(openingFactor)
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
            val openingQuantity = demoQuantity(stock.currency, positionIndex, opening = true)
            val positionId = graph.commands.execute(
                CreateInvestmentPosition(id(), accountId, instrumentId)
            ).id
            graph.commands.execute(RecordInvestmentTrade(id(), positionId, Direction.BUY,
                openingQuantity, openingPrice, at(positionStart), true,
                requireNotNull(tradeCashByAccountCurrency[accountId to stock.currency]).id))
            repeat(9) { tradeIndex ->
                val closedFixture = positionIndex % 10 == 0
                val direction = if (closedFixture && tradeIndex == 0) Direction.SELL
                    else if (closedFixture) if (tradeIndex % 2 == 1) Direction.BUY else Direction.SELL
                    else if (tradeIndex % 2 == 0) Direction.BUY else Direction.SELL
                val pairIndex = if (closedFixture) (tradeIndex - 1).coerceAtLeast(0) / 2 else tradeIndex / 2
                val quantity = if (closedFixture && tradeIndex == 0) openingQuantity
                    else demoQuantity(stock.currency, positionIndex * 3 + pairIndex, opening = false)
                val factor = BigDecimal("0.86").add(BigDecimal.valueOf(((positionIndex + tradeIndex) % 15).toLong(), 2))
                val price = R.parse_e8(stock.price.multiply(factor)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                val fee = demoFee(stock.currency, positionIndex, tradeIndex)
                val cashLinked = positionIndex % 4 != 3 || tradeIndex % 3 != 0
                val cashAccountId = if (cashLinked)
                    requireNotNull(tradeCashByAccountCurrency[accountId to stock.currency]).id else null
                graph.commands.execute(RecordInvestmentTrade(id(), positionId, direction,
                    quantity, price, at(positionStart.plusDays((tradeIndex + 1) * 260L)),
                    cashLinked,
                    cashAccountId, fee))
            }
        }

        repeat(24) { index ->
            val closed = index % 4 == 0
            val startDate = if (closed) today.minusYears(8).plusMonths(index * 3L)
                else today.minusYears(5).plusMonths(index * 2L)
            val endDate = if (closed) startDate.plusYears(1) else startDate.plusYears(7)
            val depositId = graph.commands.execute(OpenTermDeposit(id(), bankAccounts[index % bankAccounts.size],
                listOf("CNY", "USD", "HKD")[index % 3], 10_000L + index * 1_000L,
                R.parse_e8((2 + index % 4).toString()), startDate.toEpochDay(), endDate.toEpochDay(), false)).id
            if (closed) graph.commands.execute(CloseTermDeposit(id(), depositId, false))
        }
        seedLifecycleExamples(bankAccounts.first(), brokerAccounts.first(),
            instruments.zip(stocks).associate { (instrumentId, stock) ->
            stock.symbol to instrumentId
        })
    }

    private suspend fun seedCreditAccounts(accounts: List<Long>) {
        suspend fun create(accountId: Long, name: String, balanceMinor: Long, limitMinor: Long?,
            statementDay: Int, dueRule: CreditDueRule, sourceId: Long? = null): Long {
            val snapshot = graph.overview.snapshot()
            val owner = snapshot.accounts.single { it.id == accountId }
            return graph.commands.execute(SaveAccount(id(), accountId, owner.revision, owner.name, owner.note,
                listOf(CashBalanceChange("CNY", balanceMinor, null, name = name,
                    note = "演示信用账户", type = BalanceAccountType.CREDIT,
                    credit = CreditAccountInput(limitMinor, statementDay, dueRule, sourceId))))).id.let {
                graph.overview.snapshot().cash.filter { row -> row.account_id == accountId && row.name == name }.single().id
            }
        }
        create(accounts[0], "招商银行 Visa", -238_456L, 2_000_000L, 12, CreditDueRule.AfterStatementDays(20))
        create(accounts[0], "招商银行 Mastercard", 12_840L, 1_000_000L, 5, CreditDueRule.FixedDayOfMonth(20))
        val sharedRoot = create(accounts[1], "工商银行共享额度主卡", -365_000L, 5_000_000L, 25,
            CreditDueRule.AfterStatementDays(20))
        create(accounts[1], "工商银行附属卡 · 家庭", -92_000L, null, 25, CreditDueRule.AfterStatementDays(20), sharedRoot)
        create(accounts[1], "工商银行附属卡 · 出行", 30_000L, null, 31, CreditDueRule.FixedDayOfMonth(5), sharedRoot)
        create(accounts[1], "工商银行附属卡 · 网购", -46_000L, null, 31, CreditDueRule.FixedDayOfMonth(31), sharedRoot)
        create(accounts[3], "建设银行 Visa", -323_000L, 300_000L, 18, CreditDueRule.AfterStatementDays(15))
        create(accounts[4], "交通银行银联信用卡", -168_652L, 3_000_000L, 25, CreditDueRule.AfterStatementDays(20))
        create(accounts[5], "汇丰银行 Mastercard", -82_900L, 2_500_000L, 12, CreditDueRule.FixedDayOfMonth(5))
        create(accounts[2], "中国银行信用卡", 0L, 800_000L, 28, CreditDueRule.AfterStatementDays(18))
    }

    private suspend fun seedLifecycleExamples(
        bankAccountId: Long,
        brokerAccountId: Long,
        instrumentBySymbol: Map<String, Long>
    ) {
        seedCashLifecycle(bankAccountId)
        val snapshot = graph.overview.snapshot()
        val cnyCashId = snapshot.cash.single {
            it.account_id == bankAccountId &&
            it.type == BalanceAccountType.SAVINGS && it.currency.code == "CNY"
        }.id
        val usdCashId = snapshot.cash.single {
            it.account_id == brokerAccountId && it.currency.code == "USD" && it.name == "美元交易资金"
        }.id
        seedDepositLifecycle(bankAccountId, cnyCashId)
        seedInvestmentLifecycle(brokerAccountId, usdCashId, requireNotNull(instrumentBySymbol["AAPL"]),
            requireNotNull(instrumentBySymbol["META"]))
    }

    private suspend fun seedCashLifecycle(accountId: Long) {
        data class TimelineEntry(val date: LocalDate, val deltaMinor: Long, val note: String)
        val entries = listOf(
            TimelineEntry(LocalDate.of(2016, 3, 1), 500_000L, "长期账本 · 初始资金修正"),
            TimelineEntry(LocalDate.of(2018, 8, 17), -350_000L, "长期账本 · 大额支出"),
            TimelineEntry(LocalDate.of(2020, 4, 9), 823_457L, "长期账本 · 资金转入"),
            TimelineEntry(LocalDate.of(2022, 11, 23), -168_000L, "长期账本 · 账户划转"),
            TimelineEntry(LocalDate.of(2024, 6, 14), 1_200_000L, "长期账本 · 年中入金"),
            TimelineEntry(LocalDate.of(2026, 9, 18), -273_525L, "长期账本 · 近期调整")
        )
        entries.forEachIndexed { index, value ->
            val current = graph.overview.snapshot().cash.single {
                it.account_id == accountId && it.type == BalanceAccountType.SAVINGS &&
                    it.currency.code == "CNY"
            }
            val result = graph.commands.execute(SetCashBalance(id(), accountId, "CNY",
                current.balance_minor + value.deltaMinor, current.revision, current.id))
            if (index == 0) {
                graph.commands.execute(EditCashEntry(id(), result.id, 1, 475_000L,
                    at(LocalDate.of(2016, 2, 15)), "长期账本 · 第一次修正"))
                graph.commands.execute(EditCashEntry(id(), result.id, 2, 515_000L,
                    at(LocalDate.of(2016, 2, 28)), "长期账本 · 第二次修正"))
                graph.commands.execute(EditCashEntry(id(), result.id, 3, value.deltaMinor,
                    at(value.date), value.note))
            } else {
                graph.commands.execute(EditCashEntry(id(), result.id, 1, value.deltaMinor,
                    at(value.date), value.note))
            }
        }

        val snapshot = graph.overview.snapshot()
        val account = snapshot.accounts.single { it.id == accountId }
        val cny = snapshot.cash.single {
            it.account_id == accountId && it.type == BalanceAccountType.SAVINGS &&
                it.currency.code == "CNY"
        }
        graph.commands.execute(SaveAccount(id(), accountId, account.revision, account.name,
            "银行账户 · 含长期现金与存单样本", listOf(CashBalanceChange(
                "CNY", cny.balance_minor, cny.revision, cny.id,
                name = "人民币长期资金", note = "2016 年起的多次修正记录"
            ))))
    }

    private suspend fun seedDepositLifecycle(accountId: Long, cnyCashId: Long) {
        val closed = graph.commands.execute(OpenTermDeposit(id(), accountId, "CNY", 1_280_000L,
            R.parse_e8("3.25"), LocalDate.of(2017, 1, 15).toEpochDay(),
            LocalDate.of(2018, 1, 15).toEpochDay(), true, cnyCashId)).id
        graph.commands.execute(EditTermDeposit(id(), closed, 1, 1_320_000L, R.parse_e8("3.35"),
            LocalDate.of(2017, 2, 1).toEpochDay(), LocalDate.of(2018, 2, 1).toEpochDay(),
            true, null, cnyCashId))
        graph.commands.execute(EditTermDeposit(id(), closed, 2, 1_305_000L, R.parse_e8("3.45"),
            LocalDate.of(2017, 1, 20).toEpochDay(), LocalDate.of(2018, 1, 20).toEpochDay(),
            true, null, cnyCashId))
        graph.commands.execute(CloseTermDeposit(id(), closed, true, cnyCashId))
        graph.commands.execute(EditTermDeposit(id(), closed, 4, 1_298_000L, R.parse_e8("3.50"),
            LocalDate.of(2017, 1, 18).toEpochDay(), LocalDate.of(2018, 1, 18).toEpochDay(),
            true, true, cnyCashId, cnyCashId))
        graph.commands.execute(EditTermDeposit(id(), closed, 5, 1_292_000L, R.parse_e8("3.55"),
            LocalDate.of(2017, 1, 16).toEpochDay(), LocalDate.of(2018, 1, 16).toEpochDay(),
            true, true, cnyCashId, cnyCashId))

        val open = graph.commands.execute(OpenTermDeposit(id(), accountId, "CNY", 1_750_000L,
            R.parse_e8("2.60"), LocalDate.of(2024, 5, 6).toEpochDay(),
            LocalDate.of(2028, 5, 6).toEpochDay(), true, cnyCashId)).id
        graph.commands.execute(EditTermDeposit(id(), open, 1, 1_820_000L, R.parse_e8("2.75"),
            LocalDate.of(2024, 5, 8).toEpochDay(), LocalDate.of(2028, 5, 8).toEpochDay(),
            true, null, cnyCashId))
        graph.commands.execute(EditTermDeposit(id(), open, 2, 1_850_000L, R.parse_e8("2.85"),
            LocalDate.of(2024, 5, 10).toEpochDay(), LocalDate.of(2028, 5, 10).toEpochDay(),
            true, null, cnyCashId))
    }

    private suspend fun seedInvestmentLifecycle(
        accountId: Long,
        usdCashId: Long,
        appleInstrumentId: Long,
        metaInstrumentId: Long
    ) {
        val apple = graph.commands.execute(CreateInvestmentPosition(id(), accountId, appleInstrumentId)).id
        val firstBuy = graph.commands.execute(RecordInvestmentTrade(id(), apple, Direction.BUY,
            R.parse_e8("12"), R.parse_e8("120"), at(LocalDate.of(2018, 5, 2)), true,
            usdCashId, 495)).id
        graph.commands.execute(EditInvestmentTrade(id(), firstBuy, 1, Direction.BUY,
            R.parse_e8("12.5"), R.parse_e8("119"), at(LocalDate.of(2018, 5, 3)), true,
            usdCashId, 525))
        graph.commands.execute(EditInvestmentTrade(id(), firstBuy, 2, Direction.BUY,
            R.parse_e8("13"), R.parse_e8("118.75"), at(LocalDate.of(2018, 5, 4)), true,
            usdCashId, 565))
        graph.commands.execute(EditInvestmentTrade(id(), firstBuy, 3, Direction.BUY,
            R.parse_e8("12.75"), R.parse_e8("118.20"), at(LocalDate.of(2018, 5, 4)), true,
            usdCashId, 535))

        val secondBuy = graph.commands.execute(RecordInvestmentTrade(id(), apple, Direction.BUY,
            R.parse_e8("7.25"), R.parse_e8("145.80"), at(LocalDate.of(2020, 9, 18)), true,
            usdCashId, 610)).id
        graph.commands.execute(EditInvestmentTrade(id(), secondBuy, 1, Direction.BUY,
            R.parse_e8("7.5"), R.parse_e8("144.90"), at(LocalDate.of(2020, 9, 21)), true,
            usdCashId, 625))
        graph.commands.execute(EditInvestmentTrade(id(), secondBuy, 2, Direction.BUY,
            R.parse_e8("7.8"), R.parse_e8("145.15"), at(LocalDate.of(2020, 9, 22)), true,
            usdCashId, 640))

        val firstSell = graph.commands.execute(RecordInvestmentTrade(id(), apple, Direction.SELL,
            R.parse_e8("5"), R.parse_e8("190.25"), at(LocalDate.of(2023, 7, 11)), true,
            usdCashId, 330)).id
        graph.commands.execute(EditInvestmentTrade(id(), firstSell, 1, Direction.SELL,
            R.parse_e8("4.8"), R.parse_e8("191.80"), at(LocalDate.of(2023, 7, 12)), true,
            usdCashId, 340))
        graph.commands.execute(EditInvestmentTrade(id(), firstSell, 2, Direction.SELL,
            R.parse_e8("4.75"), R.parse_e8("192.20"), at(LocalDate.of(2023, 7, 13)), true,
            usdCashId, 345))
        graph.commands.execute(RecordInvestmentTrade(id(), apple, Direction.BUY,
            R.parse_e8("2.5"), R.parse_e8("220.40"), at(LocalDate.of(2025, 2, 14)), true,
            usdCashId, 420))
        graph.commands.execute(RecordInvestmentTrade(id(), apple, Direction.SELL,
            R.parse_e8("3.25"), R.parse_e8("255.60"), at(LocalDate.of(2026, 8, 24)), true,
            usdCashId, 365))

        val meta = graph.commands.execute(CreateInvestmentPosition(id(), accountId, metaInstrumentId)).id
        val metaBuy = graph.commands.execute(RecordInvestmentTrade(id(), meta, Direction.BUY,
            R.parse_e8("8"), R.parse_e8("135"), at(LocalDate.of(2019, 3, 7)), true,
            usdCashId, 520)).id
        graph.commands.execute(EditInvestmentTrade(id(), metaBuy, 1, Direction.BUY,
            R.parse_e8("8.5"), R.parse_e8("134.50"), at(LocalDate.of(2019, 3, 8)), true,
            usdCashId, 545))
        graph.commands.execute(EditInvestmentTrade(id(), metaBuy, 2, Direction.BUY,
            R.parse_e8("8.4"), R.parse_e8("134.80"), at(LocalDate.of(2019, 3, 11)), true,
            usdCashId, 535))
        val metaSell = graph.commands.execute(RecordInvestmentTrade(id(), meta, Direction.SELL,
            R.parse_e8("8.4"), R.parse_e8("224.30"), at(LocalDate.of(2022, 6, 20)), true,
            usdCashId, 410)).id
        graph.commands.execute(EditInvestmentTrade(id(), metaSell, 1, Direction.SELL,
            R.parse_e8("8.4"), R.parse_e8("226.10"), at(LocalDate.of(2022, 6, 21)), true,
            usdCashId, 425))
        graph.commands.execute(EditInvestmentTrade(id(), metaSell, 2, Direction.SELL,
            R.parse_e8("8.4"), R.parse_e8("225.75"), at(LocalDate.of(2022, 6, 22)), true,
            usdCashId, 415))
    }

    private fun at(date: LocalDate): Long = date.atTime(10, 30).atZone(clock.zone).toInstant().toEpochMilli()

    private fun demoQuantity(currency: String, index: Int, opening: Boolean): Long {
        val values = when (currency) {
            "CNY" -> if (opening) listOf("2", "5", "8", "12", "20") else listOf("1", "2", "3", "5", "8")
            "HKD" -> if (opening) listOf("5", "10", "18", "25", "40") else listOf("2", "5", "8", "12", "20")
            else -> if (opening) listOf("0.2", "0.5", "0.8", "1.2", "2") else listOf("0.1", "0.2", "0.35", "0.5", "0.8")
        }
        return R.parse_e8(values[index.mod(values.size)])
    }

    private fun demoFee(currency: String, positionIndex: Int, tradeIndex: Int): Long {
        if ((positionIndex + tradeIndex) % 13 == 0) return 0
        val variation = positionIndex * 37L + tradeIndex * 53L
        return when (currency) {
            "CNY" -> 500L + variation % 1_600L
            "HKD" -> 1_000L + variation % 2_500L
            else -> 35L + variation % 450L
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
}
