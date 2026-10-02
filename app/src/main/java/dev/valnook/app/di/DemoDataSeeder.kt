package dev.valnook.app.di

import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.CloseTermDeposit
import dev.valnook.domain.repository.OpenTermDeposit
import dev.valnook.domain.repository.RecordAccountTrade
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveAssetType
import dev.valnook.domain.repository.SaveInstrument
import dev.valnook.domain.repository.SaveOpeningPosition
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

internal class DemoDataSeeder(private val graph: AppGraph, private val clock: Clock) {
    private data class DemoStock(val name: String, val symbol: String, val market: String,
        val currency: String, val price: BigDecimal)

    suspend fun seed(displayPreferences: AppSettings) {
        graph.settings.saveSettings(AppSettings(Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")),
            FxRate(Currency.of("HKD"), Currency.of("CNY"), BigDecimal("0.92"))
        ), language = displayPreferences.language, gainLossColors = displayPreferences.gainLossColors), 0)

        val accountNames = listOf("招商证券", "华泰证券", "中信证券", "国泰君安证券", "广发证券", "中金财富",
            "富途证券", "中银国际", "汇丰证券", "盈透证券", "嘉信理财", "老虎证券")
        val accounts = accountNames.mapIndexed { index, name ->
            graph.commands.execute(SaveAccount(id(), null, null, name, "股票投资账户 ${index + 1}", listOf(
                CashBalanceChange("CNY", 1_000_000L + index * 10_000L, null, name = "人民币日常资金"),
                CashBalanceChange("USD", 200_000L + index * 1_000L, null, name = "美元交易资金"),
                CashBalanceChange("USD", 50_000L + index * 500L, null, name = "美元备用资金"),
                CashBalanceChange("HKD", 300_000L + index * 2_000L, null, name = "港币现金")
            ))).id
        }

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

        val start = clock.millis() - 180L * 86_400_000L
        val pairs = buildList {
            accounts.forEachIndexed { accountIndex, accountId ->
                repeat(7) { offset -> add(accountId to instruments[(accountIndex * 5 + offset * 7) % instruments.size]) }
            }
        }.take(80)
        pairs.forEachIndexed { positionIndex, (accountId, instrumentId) ->
            val stock = requireNotNull(stockById[instrumentId])
            val openingPrice = R.parse_e8(stock.price.multiply(BigDecimal("0.82"))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
            graph.commands.execute(SaveOpeningPosition(id(), accountId, instrumentId, R.parse_e8("10"),
                openingPrice, start + positionIndex * 60_000L))
            repeat(9) { tradeIndex ->
                val closedFixture = positionIndex % 10 == 0
                val direction = if (closedFixture && tradeIndex == 0) Direction.SELL
                    else if (closedFixture) if (tradeIndex % 2 == 1) Direction.BUY else Direction.SELL
                    else if (tradeIndex % 2 == 0) Direction.BUY else Direction.SELL
                val quantity = if (closedFixture && tradeIndex == 0) R.parse_e8("10") else R.parse_e8("1")
                val factor = BigDecimal("0.86").add(BigDecimal.valueOf(((positionIndex + tradeIndex) % 15).toLong(), 2))
                val price = R.parse_e8(stock.price.multiply(factor)
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                val fee = when (stock.currency) { "CNY" -> 500L; "HKD" -> 1_000L; else -> 100L }
                graph.commands.execute(RecordAccountTrade(id(), accountId, instrumentId, direction,
                    quantity, price, start + (positionIndex * 30L + tradeIndex + 1) * 60_000L, false,
                    feeMinor = fee))
            }
        }

        val today = LocalDate.now(clock)
        repeat(24) { index ->
            val closed = index % 4 == 0
            val startDate = if (closed) today.minusDays(180) else today.minusDays((index * 3).toLong())
            val endDate = if (closed) today.minusDays(30) else today.plusDays((30 + index * 5).toLong())
            val depositId = graph.commands.execute(OpenTermDeposit(id(), accounts[index % accounts.size],
                listOf("CNY", "USD", "HKD")[index % 3], 100_000L + index * 10_000L,
                R.parse_e8((2 + index % 4).toString()), startDate.toEpochDay(), endDate.toEpochDay(), false)).id
            if (closed) graph.commands.execute(CloseTermDeposit(id(), depositId, false))
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
}
