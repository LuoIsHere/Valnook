package dev.valnook.app.di

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillRateSource
import dev.valnook.domain.model.BillRules
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.SettlementDraft
import dev.valnook.domain.model.TransferKind
import dev.valnook.domain.repository.SaveBill
import dev.valnook.domain.repository.SettleBill
import dev.valnook.domain.repository.SettleBills
import dev.valnook.domain.repository.SettlementItem
import dev.valnook.domain.repository.WaiveReimbursement
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.random.Random

/**
 * The bundled demo's bills, from [from] to today: everyday spending and pay, transfers between the demo's own
 * accounts and to family, card repayments that match each card's cycle, and one of each case the bill pages show —
 * foreign currency, a discount, a transfer fee, refunds, reimbursements (paid back in a batch, partly, given up), a
 * bill left out of statistics, pictures and a bill dated ahead. Every note and payer has an English twin in [ENGLISH].
 */
internal class DemoBillSeeder(private val graph: DatabaseGraph, private val clock: Clock, private val from: YearMonth) {
    private val today = LocalDate.now(clock)
    private val first = from.atDay(1)
    private val random = Random(20261121)
    private lateinit var categories: List<BillCategory>
    private val cash = mutableMapOf<String, Long>()
    private val currencies = mutableMapOf<Long, String>()

    /** Each card's bills as they move it, for repayments that match its statements. */
    private val cardMoves = mutableMapOf<Long, MutableList<Pair<LocalDate, Long>>>()

    suspend fun seed() {
        categories = graph.bills.observeCategories().first()
        graph.overview.snapshot().cash.forEach { cash[it.name] = it.id; currencies[it.id] = it.currency.code }
        everyday()
        monthly()
        specials()
        repayments()
    }

    // ---- What happens most days ------------------------------------------------------------------------------

    private suspend fun everyday() {
        var date = first
        while (date <= today) {
            val workday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            if (chance(55)) expense(date, 7, 40, pick(WECHAT, ALIPAY), "food.breakfast", yuan(6, 16),
                pick("包子豆浆", "煎饼果子", "咖啡和面包", ""))
            if (workday && chance(85)) expense(date, 12, 10, ALIPAY, "food.lunch", yuan(22, 46),
                pick("公司楼下快餐", "麻辣烫", "轻食沙拉", "牛肉面", ""))
            if (chance(35)) expense(date, 19, 5, ALIPAY, "food.delivery", yuan(32, 78), pick("外卖", "外卖 · 晚饭"))
            if (workday && chance(70)) expense(date, 8, 30, ALIPAY, "transport.transit", yuan(4, 9), "地铁")
            if (chance(25)) expense(date, 15, 0, WECHAT, "food.drinks", yuan(15, 32), "咖啡")
            if (chance(12)) expense(date, 22, 10, ALIPAY, "transport.taxi", yuan(18, 55), "打车")
            if (date.dayOfWeek == DayOfWeek.SATURDAY) {
                expense(date, 10, 20, VISA, "shopping.daily", yuan(120, 360), "超市")
                expense(date, 17, 40, WECHAT, "food.fruit", yuan(25, 58), "水果")
            }
            date = date.plusDays(1)
        }
    }

    // ---- What comes round each month -------------------------------------------------------------------------

    private suspend fun monthly() {
        var month = from
        while (month <= YearMonth.from(today)) {
            fun on(day: Int) = month.atDay(day).takeIf { it <= today }
            on(10)?.let { income(it, 9, SALARY, "income.pay.salary", 26_800_00, "工资") }
            on(1)?.let { transfer(it, 8, ALIPAY, WECHAT, 500_00, "零钱充值") }
            on(12)?.let { transfer(it, 9, SALARY, MORTGAGE, 10_320_45, "房贷与信用卡") }
            on(16)?.let { transfer(it, 9, SALARY, ALIPAY, 2_800_00, "转入支付宝") }
            on(20)?.let { toFamily(it, 3_500_00) }
            on(5)?.let { expense(it, 9, 0, ALIPAY, "telecom.phone", 59_00, "话费") }
            on(8)?.let { expense(it, 20, 0, ALIPAY, "housing.utilities", yuan(150, 260), "水电燃气") }
            on(18)?.let { expense(it, 0, 5, VISA, "subscriptions.media", 25_00, "视频会员") }
            on(22)?.let { expense(it, 0, 5, ALIPAY, "subscriptions.cloud", 21_00, "云盘") }
            on(6)?.let { expense(it, 18, 20, ICBC_MAIN, "transport.fuel", yuan(300, 450), "加油") }
            on(21)?.let { expense(it, 18, 40, ICBC_MAIN, "transport.fuel", yuan(300, 450), "加油") }
            on(13)?.let { expense(it, 21, 15, ICBC_FAMILY, "shopping.daily", yuan(180, 420), "网购 · 家用") }
            on(3)?.let { expense(it, 19, 0, DRAGON, "entertainment.fitness", 199_00, "健身月卡") }
            on(17)?.let { expense(it, 14, 30, DRAGON, "transport.parking", yuan(20, 60), "停车") }
            on(24)?.let { expense(it, 19, 30, WHITE, "food.dining_out", yuan(280, 620), "朋友聚餐") }
            on(26)?.let { expense(it, 20, 10, WECHAT, "entertainment.shows", yuan(60, 90), "电影") }
            month = month.plusMonths(1)
        }
    }

    // ---- One of each case ------------------------------------------------------------------------------------

    private suspend fun specials() {
        val sep = from
        val oct = from.plusMonths(1)
        val nov = from.plusMonths(2)
        // Family cash and a gift.
        expense(sep.atDay(1), 10, 0, HOME, "education.courses", 1_200_00, "开学杂费")
        expense(sep.atDay(20), 18, 0, ALIPAY, "social.red_packets", 600_00, "同事婚礼礼金")
        transferIn(sep.atDay(15), 21, WECHAT, 300_00, "小李", "还上次的饭钱")
        income(oct.atDay(14), 20, WECHAT, "income.side.resale", 260_00, "闲鱼卖旧耳机")
        income(oct.atDay(31), 9, RESERVE, "income.investing.deposit_interest", 85_20, "活期利息")
        income(nov.atDay(6), 11, ALIPAY, "income.side.royalties", 800_00, "稿费")

        // Paid in foreign currency: a dollar subscription on the all-currency card, and yen from the yen account.
        foreign(oct.atDay(9), 3, VISA, "subscriptions.software", "USD", 39_99, 286_82, "软件年费（美元）")
        expense(nov.atDay(2), 15, 0, BOC_JPY, "shopping.beauty", 12_800, "日本代购 · 药妆")

        // A discount, a transfer with a fee, a quarter's securities transfer and a bill left out of statistics.
        save(BillDraft(BillType.EXPENSE, at(nov.atDay(11), 0, 20), 699_00, "CNY", discountMinor = 100_00,
            cashAccountId = id(ALIPAY), accountAmountMinor = 599_00, categoryId = parent("shopping.clothing"),
            subCategoryId = sub("shopping.clothing"), note = "双十一 · 外套"))
        save(BillDraft(BillType.TRANSFER, at(oct.atDay(28), 10), 5_000_00, "CNY", cashAccountId = id(SALARY),
            accountAmountMinor = 5_000_00, transferKind = TransferKind.INTERNAL, toCashAccountId = id(BOC_CNY),
            toAmountMinor = 5_000_00, feeMinor = 2_00, note = "转中行 · 跨行转账"))
        transfer(oct.atDay(5), 10, SALARY, HTSC, 6_000_00, "银证转账 · 华泰")
        save(BillDraft(BillType.EXPENSE, at(oct.atDay(5), 19), 1_200_00, "CNY", cashAccountId = id(SALARY),
            accountAmountMinor = 1_200_00, categoryId = parent("business.paid_for_others"),
            subCategoryId = sub("business.paid_for_others"), countInStats = false, note = "帮家里代付 · 物业费"))
        expense(nov.atDay(12), 20, 0, DRAGON, "shopping.home", 7_999_00, "家电 · 洗烘套装")

        // Refunds: a pair of shoes sent back in full, a delivery with a drink missing.
        val shoes = expense(oct.atDay(12), 21, 0, ICBC_FAMILY, "shopping.clothing", 459_00, "网购 · 运动鞋")
        settle(shoes, BillType.REFUND, oct.atDay(17), 11, ICBC_FAMILY, 459_00, "退货")
        val delivery = expense(nov.atDay(3), 19, 10, ALIPAY, "food.delivery", 78_00, "外卖 · 晚饭")
        settle(delivery, BillType.REFUND, nov.atDay(3), 20, ALIPAY, 15_00, "少送一份饮料")

        // A work trip: train and taxi paid back together, the hotel still waiting with its invoice attached.
        val train = expense(oct.atDay(22), 7, 30, SALARY, "transport.rail", 553_00, "出差 · 高铁往返", payer = "公司")
        val hotel = expense(oct.atDay(22), 21, 0, VISA, "business.travel", 880_00, "出差 · 酒店两晚", payer = "公司",
            pictures = listOf(picture("示例酒店", "住宿发票", listOf("房费 2 晚" to "880.00"), Color.rgb(36, 82, 140))))
        val taxi = expense(oct.atDay(23), 18, 10, ALIPAY, "transport.taxi", 68_00, "出差 · 打车", payer = "公司")
        val bills = graph.bills
        val trainBill = requireNotNull(bills.observeBill(train).first())
        val taxiBill = requireNotNull(bills.observeBill(taxi).first())
        graph.commands.execute(SettleBills(op(), id(SALARY), at(nov.atDay(10), 15), listOf(
            SettlementItem(train, trainBill.revision, 553_00, 553_00, java.math.BigDecimal.ONE, BillRateSource.SAME),
            SettlementItem(taxi, taxiBill.revision, 68_00, 68_00, java.math.BigDecimal.ONE, BillRateSource.SAME))))
        check(hotel > 0)

        // A team dinner paid up front, partly paid back; a favour for a friend, given up.
        val dinner = expense(nov.atDay(8), 20, 30, WHITE, "food.dining_out", 720_00, "部门聚餐 · 先垫付", payer = "同事")
        settle(dinner, BillType.REIMBURSEMENT, nov.atDay(9), 10, WECHAT, 540_00, "同事 AA 转回")
        val tickets = expense(sep.atDay(26), 13, 0, ALIPAY, "entertainment.shows", 90_00, "帮朋友买电影票", payer = "小王")
        val ticketBill = requireNotNull(bills.observeBill(tickets).first())
        graph.commands.execute(WaiveReimbursement(op(), tickets, ticketBill.revision, true))

        // Pictures: a supermarket receipt and a check-up's two pages.
        expense(oct.atDay(18), 10, 40, VISA, "shopping.daily", 312_60, "超市 · 囤货", pictures = listOf(
            picture("示例超市", "购物小票", listOf("牛奶" to "59.80", "大米 5kg" to "46.90", "纸巾" to "39.90",
                "洗衣液" to "52.00", "水果" to "114.00"), Color.rgb(46, 125, 91))))
        expense(nov.atDay(15), 9, 0, SALARY, "medical.checkup", 680_00, "年度体检", pictures = listOf(
            picture("示例体检中心", "体检报告 第 1 页", listOf("血常规" to "正常", "肝功能" to "正常"), Color.rgb(140, 52, 60)),
            picture("示例体检中心", "体检报告 第 2 页", listOf("心电图" to "正常", "B 超" to "正常"), Color.rgb(140, 52, 60))))

        // Dated ahead: shown as not yet posted, and already in the balance.
        expense(today.plusDays(7), 9, 0, SALARY, "housing.property", 1_260_00, "季度物业费")
    }

    /** Each card's statements in the bill months, repaid on its due day if that has come. */
    private suspend fun repayments() {
        data class Card(val name: String, val statementDay: Int, val dueAfterMonths: Long, val dueDay: Int, val payer: String)
        listOf(Card(VISA, 7, 0, 25, SALARY), Card(WHITE, 15, 1, 3, SALARY), Card(ICBC_MAIN, 25, 1, 14, MORTGAGE),
            Card(ICBC_FAMILY, 25, 1, 14, MORTGAGE), Card(DRAGON, 18, 1, 2, RESERVE)).forEach { card ->
            // Spending and refunds only: the repayments written below must not count against the next statement.
            val moves = cardMoves[id(card.name)].orEmpty().toList()
            var month = from
            while (month <= YearMonth.from(today)) {
                val statement = month.atDay(card.statementDay)
                val previous = month.minusMonths(1).atDay(card.statementDay)
                val due = month.plusMonths(card.dueAfterMonths).atDay(card.dueDay)
                val owed = -moves.filter { it.first > previous && it.first <= statement }.sumOf { it.second }
                if (statement <= today && due <= today && owed > 0) transfer(due, 10, card.payer, card.name, owed, "信用卡还款")
                month = month.plusMonths(1)
            }
        }
    }

    // ---- Writing bills ---------------------------------------------------------------------------------------

    private suspend fun expense(date: LocalDate, hour: Int, minute: Int, account: String, key: String, amount: Long,
        note: String, payer: String = "", pictures: List<BillImage>? = null): Long =
        save(BillDraft(BillType.EXPENSE, at(date, hour, minute), amount, currencyOf(account), cashAccountId = id(account),
            accountAmountMinor = amount, categoryId = parent(key), subCategoryId = sub(key), note = note,
            reimbursable = payer.isNotEmpty(), counterparty = payer, images = pictures))

    private suspend fun income(date: LocalDate, hour: Int, account: String, key: String, amount: Long, note: String) =
        save(BillDraft(BillType.INCOME, at(date, hour), amount, currencyOf(account), cashAccountId = id(account),
            accountAmountMinor = amount, categoryId = parent(key), subCategoryId = sub(key), note = note))

    private suspend fun foreign(date: LocalDate, hour: Int, account: String, key: String, currency: String,
        amount: Long, charged: Long, note: String) {
        val rate = BillRules.derivedRate(amount, Currency.of(currency), charged, Currency.of(currencyOf(account)))
        save(BillDraft(BillType.EXPENSE, at(date, hour), amount, currency, cashAccountId = id(account),
            accountAmountMinor = charged, rate = rate, rateSource = BillRateSource.DERIVED,
            categoryId = parent(key), subCategoryId = sub(key), note = note))
    }

    private suspend fun transfer(date: LocalDate, hour: Int, fromName: String, toName: String, amount: Long, note: String) =
        save(BillDraft(BillType.TRANSFER, at(date, hour), amount, currencyOf(fromName), cashAccountId = id(fromName),
            accountAmountMinor = amount, transferKind = TransferKind.INTERNAL, toCashAccountId = id(toName),
            toAmountMinor = amount, note = note))

    private suspend fun toFamily(date: LocalDate, amount: Long) =
        save(BillDraft(BillType.TRANSFER, at(date, 20), amount, "CNY", cashAccountId = id(SALARY),
            accountAmountMinor = amount, transferKind = TransferKind.TO_EXTERNAL, counterparty = "爸妈", note = "生活费"))

    private suspend fun transferIn(date: LocalDate, hour: Int, toName: String, amount: Long, payer: String, note: String) =
        save(BillDraft(BillType.TRANSFER, at(date, hour), amount, currencyOf(toName), cashAccountId = null,
            accountAmountMinor = amount, transferKind = TransferKind.FROM_EXTERNAL, toCashAccountId = id(toName),
            toAmountMinor = amount, counterparty = payer, note = note))

    private suspend fun settle(parent: Long, type: BillType, date: LocalDate, hour: Int, account: String, amount: Long,
        note: String) {
        val bill = requireNotNull(graph.bills.observeBill(parent).first())
        val id = graph.commands.execute(SettleBill(op(), parent, bill.revision, type,
            SettlementDraft(at(date, hour), amount, id(account), amount, note = note))).id
        track(account, date, amount)
        check(id > 0)
    }

    private suspend fun save(draft: BillDraft): Long {
        val id = graph.commands.execute(SaveBill(op(), null, null, draft)).id
        val date = java.time.Instant.ofEpochMilli(draft.occurredAtMs).atZone(clock.zone).toLocalDate()
        when (draft.type) {
            BillType.EXPENSE -> draft.cashAccountId?.let { trackId(it, date, -draft.accountAmountMinor) }
            BillType.INCOME -> draft.cashAccountId?.let { trackId(it, date, draft.accountAmountMinor) }
            BillType.TRANSFER -> {
                draft.cashAccountId?.let { trackId(it, date, -(draft.amountMinor + draft.feeMinor)) }
                draft.toCashAccountId?.let { trackId(it, date, draft.toAmountMinor ?: 0) }
            }
            else -> Unit
        }
        return id
    }

    private fun track(account: String, date: LocalDate, delta: Long) = trackId(id(account), date, delta)
    private fun trackId(account: Long, date: LocalDate, delta: Long) {
        if (account in CARDS.map { id(it) }) cardMoves.getOrPut(account) { mutableListOf() } += date to delta
    }

    /** A receipt-like page: a header in [tint], the shop, the title and a few lines of items with amounts. */
    private suspend fun picture(shop: String, title: String, lines: List<Pair<String, String>>, tint: Int): BillImage {
        val width = 900
        val height = 1300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 248, 243))
        canvas.drawRect(0f, 0f, width.toFloat(), 220f, Paint().apply { color = tint })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 64f; isFakeBoldText = true }
        canvas.drawText(shop, 60f, 130f, text)
        text.color = Color.rgb(40, 40, 40); text.textSize = 52f
        canvas.drawText(title, 60f, 330f, text)
        text.isFakeBoldText = false; text.textSize = 40f
        val rule = Paint().apply { color = Color.rgb(200, 196, 188); strokeWidth = 3f }
        lines.forEachIndexed { index, (item, value) ->
            val y = 460f + index * 110f
            canvas.drawText(item, 60f, y, text)
            canvas.drawText(value, width - 60f - text.measureText(value), y, text)
            canvas.drawLine(60f, y + 36f, width - 60f, y + 36f, rule)
        }
        text.textSize = 32f; text.color = Color.rgb(120, 120, 120)
        canvas.drawText("示例图片 · Sample", 60f, height - 80f, text)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it); it.toByteArray() }
        bitmap.recycle()
        return graph.images.put(bytes)
    }

    // ---- Small helpers ---------------------------------------------------------------------------------------

    private fun id(name: String) = requireNotNull(cash[name]) { "No demo sub-account $name" }
    private fun currencyOf(name: String) = requireNotNull(currencies[id(name)])
    private fun category(key: String) = requireNotNull(categories.singleOrNull { it.builtinKey == key }) { "No category $key" }
    private fun parent(key: String) = category(key).parentId ?: category(key).id
    private fun sub(key: String) = category(key).takeIf { it.parentId != null }?.id
    private fun at(date: LocalDate, hour: Int, minute: Int = 30) =
        date.atTime(hour, minute).atZone(clock.zone).toInstant().toEpochMilli()
    private fun yuan(from: Int, to: Int) = (random.nextInt(from * 10, to * 10 + 1) * 10).toLong()
    private fun chance(percent: Int) = random.nextInt(100) < percent
    private fun <T> pick(vararg values: T) = values[random.nextInt(values.size)]
    private fun op() = UUID.randomUUID().toString()

    companion object {
        const val SALARY = "招行储蓄卡"
        const val VISA = "招行 Visa 全币种"
        const val WHITE = "招行经典白"
        const val MORTGAGE = "工行储蓄卡"
        const val ICBC_MAIN = "工行信用卡主卡"
        const val ICBC_FAMILY = "工行附属卡 · 家人"
        const val RESERVE = "建行储蓄卡"
        const val DRAGON = "建行龙卡"
        const val BOC_CNY = "中行储蓄卡"
        const val BOC_JPY = "中行日元"
        const val ALIPAY = "支付宝余额"
        const val WECHAT = "微信零钱"
        const val HTSC = "华泰资金"
        const val HOME = "家中现金"
        private val CARDS = listOf(VISA, WHITE, ICBC_MAIN, ICBC_FAMILY, DRAGON)

        /** Every bill note and payer above, in English; a missing one fails the demo database build. */
        val ENGLISH = mapOf(
            "包子豆浆" to "Buns and soy milk", "煎饼果子" to "Jianbing", "咖啡和面包" to "Coffee and bread",
            "公司楼下快餐" to "Fast food near work", "麻辣烫" to "Malatang", "轻食沙拉" to "Salad",
            "牛肉面" to "Beef noodles", "外卖" to "Delivery", "外卖 · 晚饭" to "Delivery · dinner", "地铁" to "Metro",
            "咖啡" to "Coffee", "打车" to "Taxi", "超市" to "Supermarket", "水果" to "Fruit", "工资" to "Salary",
            "零钱充值" to "Top up WeChat", "房贷与信用卡" to "Mortgage and card", "转入支付宝" to "Top up Alipay",
            "生活费" to "Living money", "爸妈" to "Parents", "话费" to "Phone bill", "水电燃气" to "Utilities",
            "视频会员" to "Video membership", "云盘" to "Cloud storage", "加油" to "Fuel", "网购 · 家用" to "Online · household",
            "健身月卡" to "Gym, monthly", "停车" to "Parking", "朋友聚餐" to "Dinner with friends", "电影" to "Cinema",
            "开学杂费" to "School fees", "同事婚礼礼金" to "Colleague's wedding gift", "小李" to "Li",
            "还上次的饭钱" to "Paying back for dinner", "闲鱼卖旧耳机" to "Sold old headphones",
            "活期利息" to "Savings interest", "稿费" to "Writing fee", "软件年费（美元）" to "Software, yearly (USD)",
            "日本代购 · 药妆" to "Japanese cosmetics, bought abroad", "双十一 · 外套" to "Singles' Day · coat",
            "转中行 · 跨行转账" to "To BOC · interbank", "银证转账 · 华泰" to "To Huatai brokerage",
            "帮家里代付 · 物业费" to "Property fee paid for family", "家电 · 洗烘套装" to "Washer and dryer",
            "网购 · 运动鞋" to "Online · trainers", "退货" to "Returned", "少送一份饮料" to "A drink was missing",
            "出差 · 高铁往返" to "Work trip · train", "出差 · 酒店两晚" to "Work trip · hotel, 2 nights",
            "出差 · 打车" to "Work trip · taxi", "公司" to "Employer", "部门聚餐 · 先垫付" to "Team dinner, paid up front",
            "同事" to "Colleagues", "同事 AA 转回" to "Colleagues' shares", "帮朋友买电影票" to "Cinema tickets for a friend",
            "小王" to "Wang", "超市 · 囤货" to "Supermarket · stocking up", "年度体检" to "Annual check-up",
            "季度物业费" to "Property fee, quarterly", "信用卡还款" to "Card repayment"
        )
    }
}
