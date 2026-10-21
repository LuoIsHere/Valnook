package dev.valnook.app.di

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.valnook.domain.model.AccountGroup
import dev.valnook.domain.model.AccountIcon
import dev.valnook.domain.model.AccountIconChange
import dev.valnook.domain.model.AccountIconType
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.WalletImageFormat
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.CloseTermDeposit
import dev.valnook.domain.repository.CreateInvestmentPosition
import dev.valnook.domain.repository.EditCashEntry
import dev.valnook.domain.repository.EditInvestmentTrade
import dev.valnook.domain.repository.EditTermDeposit
import dev.valnook.domain.repository.MoveAccountsToGroup
import dev.valnook.domain.repository.OpenTermDeposit
import dev.valnook.domain.repository.RecordInvestmentTrade
import dev.valnook.domain.repository.ReorderAccountGroups
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveAccountGroup
import dev.valnook.domain.repository.SaveAssetType
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveGainLossColors
import dev.valnook.domain.repository.SaveInstrument
import dev.valnook.domain.repository.SaveLanguage
import dev.valnook.domain.repository.SetCashBalance
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.Random
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ledger of one ordinary household member, kept for about ten years: a salary card, a mortgage card, savings
 * with time deposits, foreign cash for trips, Alipay and WeChat, two brokers and the housing fund. Detail grows
 * towards today, as it does when someone starts by recording quarterly totals and later records every bill.
 * Every record goes through the app's own commands; dates are relative to the clock, so it can run on any day.
 */
internal class DemoDataSeeder(private val graph: AppGraph, private val clock: Clock) {
    private val today = LocalDate.now(clock)
    private val start = LocalDate.of(today.year - 9, 1, 3)
    private val random = Random(20261020)

    suspend fun seed(displayPreferences: AppSettings) {
        var stored = graph.settingsWriter.applyChange(SaveFinancialSettings(0, Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.12")),
            FxRate(Currency.of("HKD"), Currency.of("CNY"), BigDecimal("0.915")),
            FxRate(Currency.of("EUR"), Currency.of("CNY"), BigDecimal("8.28")),
            FxRate(Currency.of("JPY"), Currency.of("CNY"), BigDecimal("0.0472"))
        )))
        if (stored.language != displayPreferences.language) stored = graph.settingsWriter.applyChange(
            SaveLanguage(stored.revision, displayPreferences.language))
        if (stored.gainLossColors != displayPreferences.gainLossColors) graph.settingsWriter.applyChange(
            SaveGainLossColors(stored.revision, displayPreferences.gainLossColors))

        val accounts = createAccounts()
        seedCredit(accounts)
        seedGroups(accounts)
        val instruments = seedInstruments()
        seedCashHistory(accounts)
        seedInvestments(accounts, instruments)
        seedDeposits(accounts)
    }

    // ---- Accounts --------------------------------------------------------------------------------------------

    private data class Opening(val currency: String, val name: String, val amount: String, val date: LocalDate,
        val includeInAvailableCash: Boolean = true, val showOnAccountsPage: Boolean = true)

    private suspend fun createAccounts(): Map<String, Long> {
        suspend fun account(name: String, note: String, icon: AccountIconChange, vararg cash: Opening,
            showDeposits: Boolean = true, showInvestments: Boolean = true): Pair<String, Long> {
            val id = graph.commands.execute(SaveAccount(id(), null, null, name, note, cash.map {
                CashBalanceChange(it.currency, minor(it.amount, it.currency), null, name = it.name,
                    includeInAvailableCash = it.includeInAvailableCash, showOnAccountsPage = it.showOnAccountsPage)
            }, icon, showDeposits, showInvestments)).id
            // The opening balance is the first ledger entry; date it to when recording started.
            cash.forEach { opening ->
                val row = cashByName(id, opening.name)
                val entry = graph.cashPages.cashAccountPage(row.id, null, 10).singleOrNull() ?: return@forEach
                graph.commands.execute(EditCashEntry(id(), entry.id, entry.revision, entry.delta_minor,
                    at(opening.date, 9), "期初余额"))
            }
            return name to id
        }
        fun symbol(value: String) = AccountIconChange(AccountIcon(AccountIconType.SYMBOL, value))
        return mapOf(
            account(CMB, "工资卡 · 日常收支", symbol("account_balance"),
                Opening("CNY", "招行储蓄卡", "38000.00", start),
                Opening("USD", "招行美元", "1200.00", start.plusYears(2).withMonth(6))),
            account(ICBC, "房贷还款 · 家庭信用卡", symbol("credit_card"),
                Opening("CNY", "工行储蓄卡", "12000.00", start)),
            account(CCB, "储蓄与定期", symbol("savings"),
                Opening("CNY", "建行储蓄卡", "60000.00", start, includeInAvailableCash = false)),
            account(BOC, "外币 · 出境旅行", symbol("currency_exchange"),
                Opening("CNY", "中行储蓄卡", "5000.00", start.plusYears(1)),
                Opening("USD", "中行美元", "2000.00", start.plusYears(2).withMonth(6)),
                Opening("HKD", "中行港币", "12000.00", start.plusYears(2).withMonth(6), showOnAccountsPage = false),
                Opening("JPY", "中行日元", "0", start.plusYears(6).withMonth(3)),
                Opening("EUR", "中行欧元", "0", start.plusYears(8).withMonth(4)), showDeposits = true),
            account(ALIPAY, "余额与基金理财", symbol("account_balance_wallet"),
                Opening("CNY", "支付宝余额", "2000.00", start.plusYears(1).withMonth(3))),
            account(WECHAT, "零钱 · 红包与转账", symbol("payments"),
                Opening("CNY", "微信零钱", "200.00", start.plusYears(2))),
            account(HTSC, "A 股与 ETF 定投", symbol("trending_up"),
                Opening("CNY", "华泰资金", "50000.00", start)),
            account(FUTU, "港股与美股", symbol("show_chart"),
                Opening("HKD", "富途港币", "100000.00", start.plusYears(3).withMonth(5)),
                Opening("USD", "富途美元", "5000.00", start.plusYears(2).withMonth(3))),
            account(FUND, "公积金 · 不计入可用现金", symbol("apartment"),
                Opening("CNY", "公积金账户", "23000.00", start, includeInAvailableCash = false)),
            account(FAMILY, "家中现金与教育储蓄", photoIcon(),
                Opening("CNY", "家中现金", "6000.00", start.plusYears(2)), showInvestments = false)
        )
    }

    private suspend fun seedCredit(accounts: Map<String, Long>) {
        suspend fun card(account: String, name: String, limit: String?, statementDay: Int, due: CreditDueRule,
            opened: LocalDate, source: String? = null, note: String = "") {
            val owner = graph.overview.snapshot().accounts.single { it.id == accounts.getValue(account) }
            val sourceId = source?.let { cashByName(owner.id, it).id }
            graph.commands.execute(SaveAccount(id(), owner.id, owner.revision, owner.name, owner.note,
                listOf(CashBalanceChange("CNY", 0, null, name = name, note = note, type = BalanceAccountType.CREDIT,
                    credit = CreditAccountInput(limit?.let { minor(it, "CNY") }, statementDay, due, sourceId)))))
            val entry = graph.cashPages.cashAccountPage(cashByName(owner.id, name).id, null, 10).singleOrNull()
            if (entry != null) graph.commands.execute(EditCashEntry(id(), entry.id, entry.revision, entry.delta_minor,
                at(opened, 9), "开卡"))
        }
        card(CMB, "招行 Visa 全币种", "50000", 7, CreditDueRule.AfterStatementDays(18), start.plusYears(5), note = "境外消费返现")
        card(CMB, "招行经典白", "30000", 15, CreditDueRule.FixedDayOfMonth(3), start.plusYears(7), note = "年费刷 6 次免")
        card(ICBC, "工行信用卡主卡", "40000", 25, CreditDueRule.AfterStatementDays(20), start.plusYears(2))
        card(ICBC, "工行附属卡 · 家人", null, 25, CreditDueRule.AfterStatementDays(20), start.plusYears(4),
            source = "工行信用卡主卡", note = "与主卡共享额度")
        card(CCB, "建行龙卡", "10000", 18, CreditDueRule.AfterStatementDays(15), start.plusYears(8))
    }

    private suspend fun seedGroups(accounts: Map<String, Long>) {
        suspend fun group(name: String, members: List<String>): Long {
            val group = graph.commands.execute(SaveAccountGroup(id(), null, name)).id
            graph.commands.execute(MoveAccountsToGroup(id(), group, members.map(accounts::getValue)))
            return group
        }
        val banks = group(GROUP_BANKS, listOf(CMB, ICBC, CCB, BOC))
        val daily = group(GROUP_DAILY, listOf(ALIPAY, WECHAT))
        val investing = group(GROUP_INVESTING, listOf(HTSC, FUTU))
        // The housing fund and the family cash stay in the default group, listed last.
        graph.commands.execute(ReorderAccountGroups(id(), listOf(banks, daily, investing, AccountGroup.DEFAULT_ID)))
    }

    // ---- Cash ledgers ----------------------------------------------------------------------------------------

    private suspend fun seedCashHistory(accounts: Map<String, Long>) {
        val salary = cashByName(accounts.getValue(CMB), "招行储蓄卡").id
        val cmbUsd = cashByName(accounts.getValue(CMB), "招行美元").id
        val visa = cashByName(accounts.getValue(CMB), "招行 Visa 全币种").id
        val white = cashByName(accounts.getValue(CMB), "招行经典白").id
        val mortgage = cashByName(accounts.getValue(ICBC), "工行储蓄卡").id
        val icbcMain = cashByName(accounts.getValue(ICBC), "工行信用卡主卡").id
        val icbcFamily = cashByName(accounts.getValue(ICBC), "工行附属卡 · 家人").id
        val reserve = cashByName(accounts.getValue(CCB), "建行储蓄卡").id
        val dragon = cashByName(accounts.getValue(CCB), "建行龙卡").id
        val alipay = cashByName(accounts.getValue(ALIPAY), "支付宝余额").id
        val wechat = cashByName(accounts.getValue(WECHAT), "微信零钱").id
        val htsc = cashByName(accounts.getValue(HTSC), "华泰资金").id
        val futuHkd = cashByName(accounts.getValue(FUTU), "富途港币").id
        val futuUsd = cashByName(accounts.getValue(FUTU), "富途美元").id
        val housing = cashByName(accounts.getValue(FUND), "公积金账户").id
        val home = cashByName(accounts.getValue(FAMILY), "家中现金").id
        val detailed = YearMonth.from(today).minusMonths(33)

        // Early years: quarterly totals, as recorded when the app was first used.
        months(YearMonth.from(start), detailed.minusMonths(1)).filter { it.monthValue % 3 == 0 }.forEach { month ->
            val end = month.atEndOfMonth()
            adjust(salary, amount(3000, 9000), end, "季度结余")
            adjust(mortgage, amount(-400, 600), end, "季度结余")
            adjust(reserve, amount(2000, 5000), end, "转入储蓄")
            adjust(housing, 13_500_00, end.withDayOfMonth(15), "公积金缴存 · 季度")
            if (month.year >= start.year + 1) adjust(alipay, amount(-300, 500), end, "季度结余")
            if (month.year >= start.year + 4) {
                adjust(salary, -3_000_00, end.withDayOfMonth(1), "转入支付宝")
                adjust(alipay, 3_000_00, end.withDayOfMonth(1), "招行转入 · 基金定投")
            }
            if (month.year >= start.year + 2) adjust(wechat, amount(-150, 200), end, "季度结余")
        }
        years(start.year + 3, detailed.year - 1).forEach { year ->
            adjust(housing, -36_000_00, LocalDate.of(year, 1, 20), "还贷提取")
            adjust(home, -2_000_00, LocalDate.of(year, 1, 28), "春节红包")
            adjust(home, 2_000_00, LocalDate.of(year, 2, 20), "取现备用")
        }
        years(start.year, detailed.year - 1).forEach { year ->
            adjust(htsc, 12_000_00, LocalDate.of(year, 1, 6), "银证转入")
        }
        adjust(salary, -9_000_00, LocalDate.of(start.year + 4, 5, 18), "家用大件 · 冰箱")
        adjust(salary, 12_000_00, LocalDate.of(start.year + 5, 11, 2), "理财到期赎回")

        // The last 34 months: every salary, transfer and card statement.
        months(detailed, YearMonth.from(today)).forEach { month ->
            val pay = when (month.year - detailed.year) { 0 -> 23_500_00L; 1 -> 25_000_00L; else -> 26_800_00L }
            day(month, 10)?.let { adjust(salary, pay, it, "工资") }
            if (month.monthValue == 2) day(month, 8)?.let { adjust(salary, pay * 2, it, "年终奖") }
            day(month, 15)?.let { adjust(housing, 5_640_00, it, "公积金缴存") }
            if (month.monthValue == 1) day(month, 20)?.let { adjust(housing, -36_000_00, it, "还贷提取") }

            // Statements post on the statement day and are repaid on the due day from the same bank.
            val visaBill = amount(2_600, 7_400)
            day(month, 7)?.let { adjust(visa, -visaBill, it, "本期账单") }
            day(month, 25)?.let {
                adjust(visa, visaBill, it, "还款")
                adjust(salary, -visaBill, it, "还招行信用卡")
            }
            // The second card is rarely used and has no bill this month.
            val whiteBill = if (month < YearMonth.from(today)) amount(300, 1_600) else 0L
            day(month, 15)?.let { adjust(white, -whiteBill, it, "本期账单") }
            day(month.plusMonths(1), 3)?.let {
                adjust(white, whiteBill, it, "还款")
                adjust(salary, -whiteBill, it, "还招行信用卡")
            }
            val icbcBill = amount(1_200, 3_200)
            val familyBill = amount(400, 1_500)
            day(month, 25)?.let {
                adjust(icbcMain, -icbcBill, it, "本期账单")
                adjust(icbcFamily, -familyBill, it, "本期账单")
            }
            val repayment = day(month.plusMonths(1), 14)
            val monthlyMortgage = 6_820_45L
            day(month, 12)?.let {
                val transfer = monthlyMortgage + 3_500_00L
                adjust(salary, -transfer, it, "转工行 · 房贷与信用卡")
                adjust(mortgage, transfer, it, "招行转入")
            }
            day(month, 15)?.let { adjust(mortgage, -monthlyMortgage, it, "房贷月供") }
            repayment?.let {
                adjust(icbcMain, icbcBill, it, "还款")
                adjust(icbcFamily, familyBill, it, "还款")
                adjust(mortgage, -(icbcBill + familyBill), it, "还工行信用卡")
            }
            day(month, 16)?.let {
                adjust(salary, -2_800_00, it, "转入支付宝")
                adjust(alipay, 2_800_00, it, "招行转入")
            }
            day(month, 20)?.let { adjust(salary, -amount(3_000, 5_000), it, "家用转账") }
            if (month.monthValue % 3 == 1) day(month, 5)?.let {
                adjust(salary, -6_000_00, it, "银证转账 · 华泰")
                adjust(htsc, 6_000_00, it, "银证转入")
            }
            if (month.monthValue == 6) day(month, 28)?.let { adjust(reserve, 20_000_00, it, "转入储蓄") }
            if (month.monthValue == 1) {
                day(month, 26)?.let {
                    adjust(salary, -3_000_00, it, "取现 · 春节")
                    adjust(home, 3_000_00, it, "取现备用")
                }
                day(month, 27)?.let { adjust(home, -3_000_00, it, "春节红包") }
            }
            if (month.monthValue == 9) day(month, 1)?.let { adjust(home, -1_200_00, it, "开学杂费") }
            if (month < YearMonth.from(today).minusMonths(4)) {
                day(month, 28)?.let {
                    adjust(alipay, -amount(1_300, 2_100), it, "月度消费")
                    adjust(wechat, amount(-200, 300), it, "月度结余")
                }
            }
        }

        // The latest four months, recorded as they happen.
        dailySpending(alipay, wechat, today.minusMonths(4).withDayOfMonth(1))

        // Credit cards: spending since the last statement, a refund that leaves a credit balance, and a large
        // purchase that takes the new card close to its limit.
        adjust(visa, -amount(300, 900), today.minusDays(2), "超市")
        adjust(icbcMain, -2_315_60, today.minusDays(3), "加油与超市")
        adjust(icbcFamily, -1_048_00, today.minusDays(4), "网购")
        adjust(white, 86_00, today.minusDays(5), "退款 · 网购退货")
        val dragonStart = YearMonth.from(start.plusYears(8))
        months(dragonStart, YearMonth.from(today).minusMonths(1)).forEach { month ->
            val bill = amount(200, 800)
            day(month, 18)?.let { adjust(dragon, -bill, it, "本期账单") }
            day(month.plusMonths(1), 2)?.let {
                adjust(dragon, bill, it, "还款")
                adjust(reserve, -bill, it, "还建行信用卡")
            }
        }
        adjust(dragon, -7_999_00, today.minusDays(9).coerceAtLeast(today.withDayOfMonth(1)), "家电 · 洗烘套装")
        adjust(dragon, -936_20, today.minusDays(1), "加油")

        // Trips: currency exchanged from the salary card and spent abroad.
        trip(salary, cmbUsd, "USD", 7_10, LocalDate.of(start.year + 2, 7, 3), 1_500_00, "美国出差")
        val bocCny = cashByName(accounts.getValue(BOC), "中行储蓄卡").id
        val bocUsd = cashByName(accounts.getValue(BOC), "中行美元").id
        val bocHkd = cashByName(accounts.getValue(BOC), "中行港币").id
        val bocJpy = cashByName(accounts.getValue(BOC), "中行日元").id
        val bocEur = cashByName(accounts.getValue(BOC), "中行欧元").id
        adjust(bocCny, 30_000_00, LocalDate.of(start.year + 2, 3, 10), "招行转入")
        adjust(salary, -30_000_00, LocalDate.of(start.year + 2, 3, 10), "转中行")
        trip(bocCny, bocHkd, "HKD", 88, LocalDate.of(start.year + 2, 8, 2), 8_000_00, "香港旅行")
        trip(bocCny, bocJpy, "JPY", 5, LocalDate.of(start.year + 6, 3, 18), 280_000, "日本旅行")
        trip(bocCny, bocEur, "EUR", 790, LocalDate.of(start.year + 8, 4, 22), 2_400_00, "欧洲旅行")
        adjust(bocUsd, 1_800_00, LocalDate.of(start.year + 7, 11, 5), "外币汇入")
        adjust(bocCny, 12_000_00, LocalDate.of(start.year + 8, 3, 30), "招行转入")
        adjust(salary, -12_000_00, LocalDate.of(start.year + 8, 3, 30), "转中行")
        adjust(futuHkd, 60_000_00, LocalDate.of(start.year + 4, 2, 8), "入金")
        adjust(futuUsd, 2_500_00, LocalDate.of(start.year + 6, 5, 12), "换汇入金")
        adjust(futuUsd, 4_000_00, LocalDate.of(start.year + 8, 1, 9), "换汇入金")

        // Corrections: an entry typed wrong and fixed later keeps its revision history.
        val bonus = graph.cashPages.cashAccountPage(salary, null, 400).first { it.note == "年终奖" }
        graph.commands.execute(EditCashEntry(id(), bonus.id, bonus.revision, bonus.delta_minor + 1_000_00,
            bonus.occurred_at_ms, "年终奖 · 含绩效补发"))
        val corrected = graph.cashPages.cashAccountPage(salary, null, 400).first { it.id == bonus.id }
        graph.commands.execute(EditCashEntry(id(), corrected.id, corrected.revision, corrected.delta_minor,
            corrected.occurred_at_ms + 86_400_000L, "年终奖 · 含绩效补发（到账日更正）"))
    }

    /** Small Alipay and WeChat payments, most days, with the usual notes; some carry no note at all. */
    private suspend fun dailySpending(alipay: Long, wechat: Long, from: LocalDate) {
        val alipayNotes = listOf("早餐", "午餐", "超市", "打车", "地铁", "水电燃气", "话费充值", "外卖", "网购", "")
        val wechatNotes = listOf("咖啡", "便利店", "停车", "水果", "早餐", "")
        var date = from
        while (date <= today) {
            if (random.nextInt(10) < 8) {
                adjust(alipay, -amount(12, 128), date, alipayNotes[random.nextInt(alipayNotes.size)], hour = 12)
            }
            if (random.nextInt(10) < 4) {
                adjust(wechat, -amount(6, 48), date, wechatNotes[random.nextInt(wechatNotes.size)], hour = 18)
            }
            if (date.dayOfMonth == 1) adjust(wechat, 500_00, date, "支付宝转入", hour = 8)
            if (date.dayOfMonth == 1) adjust(alipay, -500_00, date, "转入微信", hour = 8)
            if (date.dayOfMonth == 21) adjust(wechat, 200_00, date, "红包 · 生日", hour = 20)
            date = date.plusDays(1)
        }
    }

    private suspend fun trip(source: Long, target: Long, currency: String, rateCnyPerHundred: Long,
        date: LocalDate, foreignMinor: Long, note: String) {
        val cnyMinor = foreignMinor * rateCnyPerHundred / if (currency == "JPY") 1 else 100
        adjust(source, -cnyMinor, date.minusDays(5), "换汇 · $note")
        adjust(target, foreignMinor, date.minusDays(5), "换汇")
        adjust(target, -foreignMinor * 7 / 10, date.plusDays(4), "$note · 消费")
    }

    // ---- Investments -----------------------------------------------------------------------------------------

    private suspend fun seedInstruments(): Map<String, Long> {
        val types = DEMO_TYPES.associateWith { graph.commands.execute(SaveAssetType(id(), null, it)).id }
        return DEMO_INSTRUMENTS.associate { item ->
            item.symbol to graph.commands.execute(SaveInstrument(id(), null, null, item.name, item.symbol,
                types.getValue(item.type), item.currency, R.parse_units(item.price, 5))).id
        }
    }

    private suspend fun seedInvestments(accounts: Map<String, Long>, instruments: Map<String, Long>) {
        val htsc = accounts.getValue(HTSC)
        val futu = accounts.getValue(FUTU)
        val alipay = accounts.getValue(ALIPAY)
        val htscCash = cashByName(htsc, "华泰资金").id
        val hkd = cashByName(futu, "富途港币").id
        val usd = cashByName(futu, "富途美元").id
        val alipayCash = cashByName(alipay, "支付宝余额").id
        suspend fun position(account: Long, symbol: String) =
            graph.commands.execute(CreateInvestmentPosition(id(), account, instruments.getValue(symbol))).id

        // A monthly ETF plan since the second year: early buys were recorded without linking cash.
        val etf = position(htsc, "510300.SH")
        months(YearMonth.from(start.plusYears(1)), YearMonth.from(today)).forEach { month ->
            val date = day(month, 3) ?: return@forEach
            trade(etf, "510300.SH", Direction.BUY, "300", date, htscCash.takeIf { month.year >= start.year + 3 })
            if (month == YearMonth.of(start.year + 4, 7) || month == YearMonth.of(start.year + 8, 2)) {
                trade(etf, "510300.SH", Direction.SELL, "3000", date.plusDays(12), htscCash)
            }
        }
        position(htsc, "600036.SH").let {
            trade(it, "600036.SH", Direction.BUY, "1000", LocalDate.of(start.year + 2, 4, 11), htscCash)
            trade(it, "600036.SH", Direction.BUY, "500", LocalDate.of(start.year + 5, 10, 25), htscCash)
        }
        position(htsc, "600900.SH").let {
            trade(it, "600900.SH", Direction.BUY, "1000", LocalDate.of(start.year + 3, 6, 15), htscCash)
            trade(it, "600900.SH", Direction.SELL, "300", LocalDate.of(start.year + 8, 3, 18), htscCash)
        }
        position(htsc, "601318.SH").let {
            trade(it, "601318.SH", Direction.BUY, "500", LocalDate.of(start.year + 1, 3, 20), null)
            trade(it, "601318.SH", Direction.SELL, "500", LocalDate.of(start.year + 4, 2, 22), htscCash)
        }
        position(htsc, "019740.SH").let {
            trade(it, "019740.SH", Direction.BUY, "200", LocalDate.of(start.year + 7, 3, 20), htscCash)
        }
        // Added to the list while waiting for a better price; no trade yet.
        position(htsc, "600519.SH")

        position(futu, "0700.HK").let {
            trade(it, "0700.HK", Direction.BUY, "100", LocalDate.of(start.year + 3, 7, 8), hkd)
            trade(it, "0700.HK", Direction.BUY, "100", LocalDate.of(start.year + 5, 10, 24), hkd)
        }
        position(futu, "1810.HK").let {
            trade(it, "1810.HK", Direction.BUY, "2000", LocalDate.of(start.year + 4, 3, 9), hkd)
            trade(it, "1810.HK", Direction.SELL, "1000", LocalDate.of(start.year + 8, 6, 17), hkd)
        }
        position(futu, "3690.HK").let {
            trade(it, "3690.HK", Direction.BUY, "200", LocalDate.of(start.year + 4, 2, 17), hkd)
            trade(it, "3690.HK", Direction.SELL, "200", LocalDate.of(start.year + 6, 5, 23), hkd)
        }
        position(futu, "AAPL").let { apple ->
            val first = trade(apple, "AAPL", Direction.BUY, "10", LocalDate.of(start.year + 3, 8, 12), usd)
            // The first buy was entered with the wrong price and quantity, then corrected twice.
            val trade = requireNotNull(graph.investments.get_trade(first))
            graph.commands.execute(EditInvestmentTrade(id(), first, trade.revision, Direction.BUY, R.parse_e8("12"),
                trade.execution_price_e8 + R.parse_e8("1.5"), trade.occurred_at_ms, true, usd, trade.fee_minor))
            graph.commands.execute(EditInvestmentTrade(id(), first, trade.revision + 1, Direction.BUY, R.parse_e8("10"),
                trade.execution_price_e8, trade.occurred_at_ms, true, usd, trade.fee_minor + 50))
            trade(apple, "AAPL", Direction.BUY, "5", LocalDate.of(start.year + 5, 6, 14), usd)
            trade(apple, "AAPL", Direction.SELL, "3", LocalDate.of(start.year + 7, 12, 9), usd)
            trade(apple, "AAPL", Direction.BUY, "3", LocalDate.of(start.year + 8, 4, 7), usd)
        }
        position(futu, "NVDA").let {
            trade(it, "NVDA", Direction.BUY, "2", LocalDate.of(start.year + 6, 1, 18), usd)
            trade(it, "NVDA", Direction.BUY, "1.5", LocalDate.of(start.year + 7, 9, 10), usd)
        }
        position(futu, "VOO").let { voo ->
            // Fractional shares: a fixed 500 USD every quarter.
            months(YearMonth.of(start.year + 8, 1), YearMonth.from(today)).filter { it.monthValue % 3 == 1 }
                .forEach { month -> day(month, 15)?.let { date ->
                    val units = BigDecimal("500").divide(price("VOO", date), 8, RoundingMode.DOWN)
                    trade(voo, "VOO", Direction.BUY, units.toPlainString(), date, usd)
                } }
        }
        position(futu, "META").let {
            trade(it, "META", Direction.BUY, "6", LocalDate.of(start.year + 2, 3, 7), usd)
            trade(it, "META", Direction.SELL, "6", LocalDate.of(start.year + 5, 6, 21), usd)
        }

        // Alipay funds: a monthly plan, and one lump sum bought near a peak and half sold later.
        val blueChip = position(alipay, "005827")
        months(YearMonth.of(start.year + 4, 1), YearMonth.from(today)).forEach { month ->
            day(month, 8)?.let { date ->
                val units = BigDecimal("1000").divide(price("005827", date), 2, RoundingMode.DOWN)
                trade(blueChip, "005827", Direction.BUY, units.toPlainString(), date, alipayCash)
            }
        }
        adjust(alipayCash, 20_000_00, LocalDate.of(start.year + 4, 2, 9), "招行转入 · 基金")
        adjust(cashByName(accounts.getValue(CMB), "招行储蓄卡").id, -20_000_00, LocalDate.of(start.year + 4, 2, 9), "转入支付宝 · 基金")
        position(alipay, "161725").let {
            val date = LocalDate.of(start.year + 4, 2, 10)
            val units = BigDecimal("20000").divide(price("161725", date), 2, RoundingMode.DOWN)
            trade(it, "161725", Direction.BUY, units.toPlainString(), date, alipayCash)
            trade(it, "161725", Direction.SELL, units.divide(BigDecimal(2), 2, RoundingMode.DOWN).toPlainString(),
                LocalDate.of(start.year + 7, 9, 26), alipayCash)
        }
    }

    /** Records a trade at the demo price of that day and returns its id. */
    private suspend fun trade(position: Long, symbol: String, direction: Direction, quantity: String,
        date: LocalDate, cash: Long?): Long {
        val item = DEMO_INSTRUMENTS.single { it.symbol == symbol }
        val unit = price(symbol, date)
        val notional = unit.multiply(BigDecimal(quantity))
        val fee = when {
            random.nextInt(12) == 0 -> BigDecimal.ZERO
            item.type == "基金" && item.currency == "CNY" && symbol.length == 6 ->
                notional.multiply(BigDecimal("0.0015"))
            item.type == "基金" && item.currency == "CNY" -> notional.multiply(BigDecimal("0.0001")).max(BigDecimal("0.1"))
            item.currency == "CNY" -> notional.multiply(BigDecimal("0.00025")).max(BigDecimal("5"))
            item.currency == "HKD" -> notional.multiply(BigDecimal("0.0003")).add(BigDecimal("15"))
            else -> BigDecimal("0.99").add(BigDecimal(quantity).multiply(BigDecimal("0.005")))
        }.let { minor(it.setScale(2, RoundingMode.HALF_UP).toPlainString(), item.currency) }
        return graph.commands.execute(RecordInvestmentTrade(id(), position, direction, R.parse_e8(quantity),
            R.parse_e8(unit.toPlainString()), at(date, 10), cash != null, cash, fee)).id
    }

    // ---- Deposits --------------------------------------------------------------------------------------------

    private suspend fun seedDeposits(accounts: Map<String, Long>) {
        val ccb = accounts.getValue(CCB)
        val reserve = cashByName(ccb, "建行储蓄卡").id
        suspend fun open(account: Long, currency: String, principal: String, rate: String, from: LocalDate,
            to: LocalDate, cash: Long?) = graph.commands.execute(OpenTermDeposit(id(), account, currency,
            minor(principal, currency), R.parse_e8(rate), from.toEpochDay(), to.toEpochDay(), cash != null, cash)).id

        // Matured and closed back into the savings card, one of them corrected after a typing mistake.
        val fiveYears = open(ccb, "CNY", "60000", "3.25", LocalDate.of(start.year + 1, 3, 12),
            LocalDate.of(start.year + 6, 3, 12), reserve)
        graph.commands.execute(EditTermDeposit(id(), fiveYears, 1, minor("60000", "CNY"), R.parse_e8("3.575"),
            LocalDate.of(start.year + 1, 3, 12).toEpochDay(), LocalDate.of(start.year + 6, 3, 12).toEpochDay(),
            true, null, reserve))
        graph.commands.execute(CloseTermDeposit(id(), fiveYears, true, reserve))
        val oneYear = open(ccb, "CNY", "20000", "1.95", LocalDate.of(start.year + 5, 6, 20),
            LocalDate.of(start.year + 6, 6, 20), reserve)
        graph.commands.execute(CloseTermDeposit(id(), oneYear, true, reserve))
        // A ladder still running.
        open(ccb, "CNY", "100000", "2.35", LocalDate.of(start.year + 7, 3, 15), LocalDate.of(start.year + 10, 3, 15), reserve)
        open(ccb, "CNY", "50000", "1.45", LocalDate.of(start.year + 8, 11, 5), LocalDate.of(start.year + 9, 11, 5), reserve)
        open(ccb, "CNY", "20000", "1.30", today.minusMonths(4).withDayOfMonth(1),
            today.minusMonths(4).withDayOfMonth(1).plusMonths(6), reserve)

        val boc = accounts.getValue(BOC)
        open(boc, "USD", "3000", "4.00", today.minusMonths(10).withDayOfMonth(10),
            today.minusMonths(10).withDayOfMonth(10).plusYears(1), cashByName(boc, "中行美元").id)
        // Matured last month and not yet handled, so it shows as due.
        open(boc, "HKD", "5000", "2.80", today.minusMonths(4).withDayOfMonth(1).minusDays(1),
            today.minusMonths(1).withDayOfMonth(1).minusDays(1), cashByName(boc, "中行港币").id)

        val family = accounts.getValue(FAMILY)
        open(family, "CNY", "50000", "2.75", LocalDate.of(start.year + 5, 9, 1), LocalDate.of(start.year + 10, 9, 1), null)
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private suspend fun cashByName(accountId: Long, name: String): CashAccount =
        graph.cash.observe_cash(accountId).first().single { it.name == name }

    /** Records a balance change through the app, dated to [date]. */
    private suspend fun adjust(cashId: Long, delta: Long, date: LocalDate, note: String, hour: Int = 9) {
        if (delta == 0L || date > today) return
        val cash = requireNotNull(graph.cash.observeCashAccount(cashId).first())
        val result = graph.commands.execute(SetCashBalance(id(), cash.account_id, cash.currency.code,
            cash.balance_minor + delta, cash.revision, cash.id, cash.name, cash.note))
        graph.commands.execute(EditCashEntry(id(), result.id, 1, delta, at(date, hour), note))
    }

    private fun amount(fromYuan: Int, toYuan: Int): Long =
        (fromYuan * 100L) + (random.nextDouble() * (toYuan - fromYuan) * 100).toLong()

    private fun months(from: YearMonth, to: YearMonth): List<YearMonth> =
        generateSequence(from) { it.plusMonths(1) }.takeWhile { it <= to }.toList()

    private fun years(from: Int, to: Int): IntRange = from..to

    private fun day(month: YearMonth, day: Int): LocalDate? =
        month.atDay(minOf(day, month.lengthOfMonth())).takeIf { it <= today && it >= start }

    private fun at(date: LocalDate, hour: Int): Long = date.atTime(hour, 30).atZone(clock.zone).toInstant().toEpochMilli()
        .coerceAtMost(clock.millis())

    private fun price(symbol: String, date: LocalDate): BigDecimal = demoPrice(symbol, date, today)

    private fun id(): String = UUID.randomUUID().toString()

    /** A 256 px account photo: a soft gradient with a house outline, standing in for a family picture. */
    private fun photoIcon(): AccountIconChange {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 256f, 256f, 0xffe9b38a.toInt(), 0xff7a8fb1.toInt(), Shader.TileMode.CLAMP)
        })
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xf2ffffff.toInt(); strokeWidth = 14f
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(64f, 132f); lineTo(128f, 76f); lineTo(192f, 132f)
            moveTo(84f, 120f); lineTo(84f, 188f); lineTo(172f, 188f); lineTo(172f, 120f)
        }, line)
        canvas.drawCircle(196f, 64f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xfffff3d6.toInt() })
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it); it.toByteArray() }
        bitmap.recycle()
        return AccountIconChange(AccountIcon(AccountIconType.IMAGE, WalletImageFormat.digest(bytes)), bytes)
    }

    internal data class DemoInstrument(val symbol: String, val name: String, val english: String, val type: String,
        val currency: String, val price: String, val listed: Int)

    companion object {
        const val CMB = "招商银行"
        const val ICBC = "中国工商银行"
        const val CCB = "中国建设银行"
        const val BOC = "中国银行"
        const val ALIPAY = "支付宝"
        const val WECHAT = "微信"
        const val HTSC = "华泰证券"
        const val FUTU = "富途证券"
        const val FUND = "住房公积金"
        const val FAMILY = "家庭备用金"
        const val GROUP_BANKS = "银行卡"
        const val GROUP_DAILY = "日常"
        const val GROUP_INVESTING = "投资"

        val DEMO_TYPES = listOf("A股股票", "港股股票", "美股股票", "基金", "债券", "黄金")

        /** [DemoInstrument.listed] is the year offset from the start of the ledger when prices begin. */
        val DEMO_INSTRUMENTS = listOf(
            DemoInstrument("600519.SH", "贵州茅台", "Kweichow Moutai", "A股股票", "CNY", "1468.20", 0),
            DemoInstrument("600036.SH", "招商银行", "China Merchants Bank", "A股股票", "CNY", "42.15", 0),
            DemoInstrument("600900.SH", "长江电力", "China Yangtze Power", "A股股票", "CNY", "29.16", 0),
            DemoInstrument("601318.SH", "中国平安", "Ping An Insurance", "A股股票", "CNY", "57.40", 0),
            DemoInstrument("300750.SZ", "宁德时代", "CATL", "A股股票", "CNY", "238.50", 1),
            DemoInstrument("600941.SH", "中国移动", "China Mobile", "A股股票", "CNY", "112.30", 5),
            DemoInstrument("0700.HK", "腾讯控股", "Tencent", "港股股票", "HKD", "598.00", 0),
            DemoInstrument("1810.HK", "小米集团-W", "Xiaomi", "港股股票", "HKD", "52.35", 1),
            DemoInstrument("3690.HK", "美团-W", "Meituan", "港股股票", "HKD", "112.70", 1),
            DemoInstrument("0005.HK", "汇丰控股", "HSBC Holdings", "港股股票", "HKD", "104.20", 0),
            DemoInstrument("AAPL", "Apple", "Apple", "美股股票", "USD", "258.40", 0),
            DemoInstrument("NVDA", "NVIDIA", "NVIDIA", "美股股票", "USD", "188.25", 0),
            DemoInstrument("MSFT", "Microsoft", "Microsoft", "美股股票", "USD", "518.70", 0),
            DemoInstrument("META", "Meta Platforms", "Meta Platforms", "美股股票", "USD", "742.30", 0),
            DemoInstrument("510300.SH", "沪深300ETF", "CSI 300 ETF", "基金", "CNY", "4.124", 0),
            DemoInstrument("005827", "易方达蓝筹精选混合", "E Fund Blue Chip Fund", "基金", "CNY", "1.4521", 1),
            DemoInstrument("161725", "招商中证白酒指数", "CMF CSI Liquor Index Fund", "基金", "CNY", "0.8936", 0),
            DemoInstrument("VOO", "Vanguard S&P 500 ETF", "Vanguard S&P 500 ETF", "基金", "USD", "598.12", 0),
            DemoInstrument("019740.SH", "24国债01", "China Government Bond 2401", "债券", "CNY", "101.235", 7)
        )

        // A getter: it joins maps declared further down.
        val ENGLISH: Map<String, String> get() = mapOf(
            CMB to "China Merchants Bank", ICBC to "ICBC", CCB to "China Construction Bank", BOC to "Bank of China",
            ALIPAY to "Alipay", WECHAT to "WeChat", HTSC to "Huatai Securities", FUTU to "Futu Securities",
            FUND to "Housing fund", FAMILY to "Family reserve",
            "工资卡 · 日常收支" to "Salary card · daily spending",
            "房贷还款 · 家庭信用卡" to "Mortgage payments · family credit cards",
            "储蓄与定期" to "Savings and time deposits",
            "外币 · 出境旅行" to "Foreign currency · travel",
            "余额与基金理财" to "Balance and mutual funds",
            "零钱 · 红包与转账" to "Change · gifts and transfers",
            "A 股与 ETF 定投" to "A-shares and a monthly ETF plan",
            "港股与美股" to "Hong Kong and US stocks",
            "公积金 · 不计入可用现金" to "Housing fund · not in available cash",
            "家中现金与教育储蓄" to "Cash at home and education savings",
            "招行储蓄卡" to "CMB debit card", "招行美元" to "CMB USD", "招行 Visa 全币种" to "CMB Visa",
            "招行经典白" to "CMB Classic White", "工行储蓄卡" to "ICBC debit card", "工行信用卡主卡" to "ICBC credit card",
            "工行附属卡 · 家人" to "ICBC supplementary · family", "建行储蓄卡" to "CCB debit card",
            "建行龙卡" to "CCB Long Card", "中行储蓄卡" to "BOC debit card", "中行美元" to "BOC USD",
            "中行港币" to "BOC HKD", "中行日元" to "BOC JPY", "中行欧元" to "BOC EUR",
            "支付宝余额" to "Alipay balance", "微信零钱" to "WeChat change", "华泰资金" to "Huatai cash",
            "富途港币" to "Futu HKD", "富途美元" to "Futu USD", "公积金账户" to "Housing fund account",
            "家中现金" to "Cash at home",
            "A股股票" to "China A-shares", "港股股票" to "Hong Kong stocks", "美股股票" to "US stocks",
            "基金" to "Funds", "债券" to "Bonds", "黄金" to "Gold",
            GROUP_BANKS to "Bank cards", GROUP_DAILY to "Everyday", GROUP_INVESTING to "Investing",
            "境外消费返现" to "Cashback abroad", "年费刷 6 次免" to "Fee waived after 6 purchases a year",
            "与主卡共享额度" to "Shares the primary card's limit"
        ) + DEMO_INSTRUMENTS.associate { it.name to it.english } + ENTRY_ENGLISH

        /** Ledger notes, in both languages. Every note the seeder writes must be listed here. */
        val ENTRY_ENGLISH = mapOf(
            "期初余额" to "Opening balance", "开卡" to "Card opened", "季度结余" to "Quarterly net",
            "转入储蓄" to "Moved to savings", "公积金缴存 · 季度" to "Housing fund · quarterly",
            "转入支付宝" to "To Alipay", "招行转入 · 基金定投" to "From CMB · fund plan", "还贷提取" to "Withdrawn for the mortgage",
            "春节红包" to "Spring Festival red envelopes", "取现备用" to "Cash withdrawn for home", "银证转入" to "From the bank",
            "家用大件 · 冰箱" to "Household · fridge", "理财到期赎回" to "Wealth product redeemed", "工资" to "Salary",
            "年终奖" to "Year-end bonus", "公积金缴存" to "Housing fund contribution", "本期账单" to "Statement",
            "还款" to "Repayment", "还招行信用卡" to "CMB credit card repayment",
            "转工行 · 房贷与信用卡" to "To ICBC · mortgage and cards", "招行转入" to "From CMB", "房贷月供" to "Mortgage payment",
            "还工行信用卡" to "ICBC credit card repayment", "家用转账" to "Household transfer", "银证转账 · 华泰" to "To Huatai",
            "取现 · 春节" to "Cash · Spring Festival", "开学杂费" to "School fees", "月度消费" to "Monthly spending",
            "月度结余" to "Monthly net", "早餐" to "Breakfast", "午餐" to "Lunch", "超市" to "Supermarket", "打车" to "Taxi",
            "地铁" to "Metro", "水电燃气" to "Utilities", "话费充值" to "Phone top-up", "外卖" to "Food delivery",
            "网购" to "Online shopping", "咖啡" to "Coffee", "便利店" to "Convenience store", "停车" to "Parking",
            "水果" to "Fruit", "支付宝转入" to "From Alipay", "转入微信" to "To WeChat", "红包 · 生日" to "Birthday red envelope",
            "加油与超市" to "Fuel and supermarket", "退款 · 网购退货" to "Refund · online return",
            "还建行信用卡" to "CCB credit card repayment", "家电 · 洗烘套装" to "Appliances · washer and dryer",
            "加油" to "Fuel", "换汇" to "Currency exchange", "转中行" to "To BOC", "外币汇入" to "Foreign remittance received",
            "入金" to "Deposit", "换汇入金" to "Exchanged and deposited", "招行转入 · 基金" to "From CMB · funds",
            "转入支付宝 · 基金" to "To Alipay · funds", "年终奖 · 含绩效补发" to "Year-end bonus · with performance pay",
            "年终奖 · 含绩效补发（到账日更正）" to "Year-end bonus · with performance pay (date corrected)"
        ) + listOf("美国出差" to "US business trip", "香港旅行" to "Hong Kong trip", "日本旅行" to "Japan trip",
            "欧洲旅行" to "Europe trip").flatMap { (zh, en) ->
            listOf("换汇 · $zh" to "Currency exchange · $en", "$zh · 消费" to "$en · spending")
        }

        private fun minor(amount: String, currency: String): Long =
            R.parse_units(amount, Currency.of(currency).fraction_digits)

        /**
         * A smooth synthetic price path that ends exactly at the current price: a yearly drift and a slow wave, so
         * charts, trades and history agree. Rounded to the instrument's quoting precision.
         */
        fun demoPrice(symbol: String, date: LocalDate, today: LocalDate): BigDecimal {
            val item = DEMO_INSTRUMENTS.single { it.symbol == symbol }
            val seed = abs(symbol.hashCode())
            val growth = 1.02 + (seed % 9) / 100.0
            val wave = .08 + (seed / 9 % 10) / 100.0
            val period = 1.6 + (seed / 90 % 25) / 10.0
            val phase = (seed / 2250 % 628) / 100.0
            val years = (today.toEpochDay() - date.toEpochDay()) / 365.25
            val factor = growth.pow(-years) * (1 + wave * sin(2 * PI * years / period + phase)) / (1 + wave * sin(phase))
            val scale = BigDecimal(item.price).scale()
            return BigDecimal(item.price).multiply(BigDecimal(factor)).setScale(scale, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE.movePointLeft(scale))
        }
    }
}
