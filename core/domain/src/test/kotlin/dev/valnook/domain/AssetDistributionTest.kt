package dev.valnook.domain

import dev.valnook.domain.calculation.AssetDistribution
import dev.valnook.domain.model.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class AssetDistributionTest {
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val hkd = Currency.of("HKD")
    private fun position(id: Long, account: Long, type: String, quantity: Long = 1000000000) =
        Investment(id, account, id, type, "Fund $id", "F$id", usd, quantity, 10000000000, 0)
    private fun snapshot(rates: List<FxRate> = listOf(FxRate(usd, cny, BigDecimal("7.2")))) = AssetSnapshot(
        listOf(SavingsAccount(1, "Broker", ""), SavingsAccount(2, "Wallet", ""), SavingsAccount(3, "Card", "")),
        listOf(CashBalance(1, cny, 100000, 1), CashBalance(2, cny, 50000, 1), CashBalance(3, hkd, -20000, 1)),
        listOf(TermDeposit(1, 1, usd, 100000, 0, 1, 366, 1000, false, false, null),
            TermDeposit(2, 1, usd, 500000, 0, 1, 366, 1000, true, false, null)),
        listOf(position(1, 1, "Stocks"), position(2, 1, "Bonds"), position(3, 1, "Stocks", quantity = 0)),
        emptyList(), AppSettings(cny, rates, 1))
    private fun values(parts: List<dev.valnook.domain.calculation.DistributionPart>) =
        parts.associate { it.label to it.value.stripTrailingZeros().toPlainString() }

    @Test fun parts_are_valued_in_the_base_currency() {
        val result = AssetDistribution.calculate(snapshot(listOf(FxRate(usd, cny, BigDecimal("7.2")),
            FxRate(hkd, cny, BigDecimal("0.9")))))
        assertEquals(cny, result.currency)
        // Accounts keep their order and a negative total; closed deposits and sold-out positions are left out.
        assertEquals(listOf("Broker", "Wallet", "Card"), result.accounts.map { it.label })
        assertEquals(mapOf("Broker" to "22600", "Wallet" to "500", "Card" to "-180"), values(result.accounts))
        assertEquals(mapOf("Bonds" to "7200", "Stocks" to "7200"), values(result.assetTypes))
        assertEquals(listOf("Bonds", "Stocks"), result.assetTypes.map { it.label })
        assertEquals(mapOf("CNY" to "1500", "HKD" to "-180", "USD" to "21600"), values(result.currencies))
        // Each currency also keeps its own amount: USD 1,000 deposit + 2,000 in funds; HKD -200.
        assertEquals(mapOf("CNY" to "1500", "HKD" to "-200", "USD" to "3000"),
            result.currencies.associate { it.label to it.native!!.stripTrailingZeros().toPlainString() })
        // Accounts and asset types are in the base currency only.
        assertTrue((result.accounts + result.assetTypes).all { it.native == null })
    }

    @Test fun a_currency_without_a_rate_is_left_out() {
        val result = AssetDistribution.calculate(snapshot())
        assertEquals(listOf("CNY", "USD"), result.currencies.map { it.label })
        assertEquals(0, result.accounts.single { it.label == "Card" }.value.signum())
    }
}
