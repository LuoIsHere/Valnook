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
    suspend fun seed(displayPreferences: AppSettings) {
        graph.settings.saveSettings(AppSettings(Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")),
            FxRate(Currency.of("HKD"), Currency.of("CNY"), BigDecimal("0.92"))
        ), language = displayPreferences.language, gainLossColors = displayPreferences.gainLossColors), 0)

        val accountNames = listOf("招商银行", "Schwab", "IBKR", "汇丰", "中银香港", "富途",
            "家庭资产", "长期储备", "教育基金", "退休账户", "旅行资金", "测试组合")
        val accounts = accountNames.mapIndexed { index, name ->
            graph.commands.execute(SaveAccount(id(), null, null, name, "演示数据 ${index + 1}", listOf(
                CashBalanceChange("CNY", 1_000_000L + index * 10_000L, null, name = "人民币日常资金"),
                CashBalanceChange("USD", 200_000L + index * 1_000L, null, name = "美元交易资金"),
                CashBalanceChange("USD", 50_000L + index * 500L, null, name = "美元备用资金"),
                CashBalanceChange("HKD", 300_000L + index * 2_000L, null, name = "港币现金")
            ))).id
        }

        val typeNames = listOf("股票", "指数基金", "债券", "商品", "REIT", "货币基金", "加密资产", "其他")
        val types = typeNames.map { graph.commands.execute(SaveAssetType(id(), null, it)).id }
        val currencies = listOf("CNY", "USD", "HKD", "EUR")
        val instruments = (0 until 60).map { index ->
            val familiar = listOf("QQQ", "VOO", "HSI", "沪深300", "黄金", "全球债券")
            val symbol = familiar.getOrNull(index) ?: "DEMO${index + 1}"
            graph.commands.execute(SaveInstrument(id(), null, null,
                if (index < familiar.size) "$symbol 演示标的" else "演示标的 ${index + 1}",
                symbol, types[index % types.size], currencies[index % currencies.size],
                5_000_000L + index * 137_500L)).id
        }

        val start = clock.millis() - 180L * 86_400_000L
        val pairs = buildList {
            accounts.forEachIndexed { accountIndex, accountId ->
                repeat(7) { offset -> add(accountId to instruments[(accountIndex * 5 + offset * 7) % instruments.size]) }
            }
        }.take(80)
        pairs.forEachIndexed { positionIndex, (accountId, instrumentId) ->
            val openingPrice = R.parse_e8((80 + positionIndex % 23).toString())
            graph.commands.execute(SaveOpeningPosition(id(), accountId, instrumentId, R.parse_e8("10"),
                openingPrice, start + positionIndex * 60_000L))
            repeat(25) { tradeIndex ->
                val closedFixture = positionIndex % 10 == 0
                val direction = if (closedFixture && tradeIndex == 0) Direction.SELL
                    else if (closedFixture) if (tradeIndex % 2 == 1) Direction.BUY else Direction.SELL
                    else if (tradeIndex % 2 == 0) Direction.BUY else Direction.SELL
                val quantity = if (closedFixture && tradeIndex == 0) R.parse_e8("10") else R.parse_e8("1")
                val price = R.parse_e8((75 + (positionIndex * 3 + tradeIndex * 5) % 55).toString())
                graph.commands.execute(RecordAccountTrade(id(), accountId, instrumentId, direction,
                    quantity, price, start + (positionIndex * 30L + tradeIndex + 1) * 60_000L, false))
            }
        }

        val today = LocalDate.now(clock)
        repeat(24) { index ->
            val closed = index % 4 == 0
            val startDate = if (closed) today.minusDays(180) else today.minusDays((index * 3).toLong())
            val endDate = if (closed) today.minusDays(30) else today.plusDays((30 + index * 5).toLong())
            val depositId = graph.commands.execute(OpenTermDeposit(id(), accounts[index % accounts.size],
                currencies[index % currencies.size], 100_000L + index * 10_000L,
                R.parse_e8((2 + index % 4).toString()), startDate.toEpochDay(), endDate.toEpochDay(), false)).id
            if (closed) graph.commands.execute(CloseTermDeposit(id(), depositId, false))
        }
    }

    private fun id(): String = UUID.randomUUID().toString()
}
