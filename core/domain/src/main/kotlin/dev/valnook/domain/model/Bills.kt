package dev.valnook.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

enum class BillType { EXPENSE, INCOME, TRANSFER, REFUND, REIMBURSEMENT }

/**
 * What an account's ledger needs to name a bill's cash entry as the bills page names the bill: its note, its
 * category names, and for a transfer both sides ("Account · sub-account", null when external or deleted).
 */
data class CashEntryBill(val note: String, val transfer: Boolean,
    val categoryKey: String?, val categoryName: String?, val subKey: String?, val subName: String?,
    val from: String?, val to: String?, val fromExternal: Boolean, val toExternal: Boolean, val counterparty: String) {
    /** "Food-Lunch", or the one category left; null when the bill has none. */
    fun categoryTitle(english: Boolean): String? {
        fun name(key: String?, custom: String?) = (custom ?: BillCategoryPresets.name(key, english))?.takeIf { it.isNotEmpty() }
        val top = name(categoryKey, categoryName)
        val sub = name(subKey, subName)
        return if (top != null && sub != null) "$top-$sub" else sub ?: top
    }
}

/** Which side of a transfer is one of the user's own sub-accounts. An external side has no account or currency. */
enum class TransferKind { INTERNAL, TO_EXTERNAL, FROM_EXTERNAL }

/** Where a bill's exchange rate came from; [DERIVED] means worked out from the amount the account actually moved. */
enum class BillRateSource { SAME, AUTO, MANUAL, ONE_TIME, DERIVED }

enum class ReimburseState { NONE, PENDING, PARTIAL, DONE, WAIVED }

enum class BillCategoryKind { EXPENSE, INCOME }

data class BillCategory(
    val id: Long,
    val kind: BillCategoryKind,
    val parentId: Long?,
    /** Set on preset categories; their name follows the language until the user renames them. */
    val builtinKey: String?,
    val customName: String?,
    val iconKey: String,
    val colorKey: String,
    val sortOrder: Long,
    val hidden: Boolean,
    val lastCashAccountId: Long?,
    val revision: Long,
) {
    fun name(english: Boolean): String = customName ?: BillCategoryPresets.name(builtinKey, english) ?: ""
}

/**
 * What the bill form hands to saving, for a new bill and an edit alike.
 *
 * - Expense and income: [amountMinor] (before [discountMinor]) is in [currencyCode]; the sub-account
 *   [cashAccountId] moves by [accountAmountMinor] in its own currency, which is the paid amount times [rate]. With no
 *   [cashAccountId] the bill moves no account: [accountAmountMinor] is the paid amount and the rate 1.
 * - Transfer: [amountMinor] = [accountAmountMinor] leaves [cashAccountId] in its currency (plus [feeMinor]) and
 *   [toAmountMinor] arrives in [toCashAccountId]; [rate] is how much of the destination currency one unit of the
 *   source buys. An external side has no account: from outside, only [toCashAccountId] and [toAmountMinor] are set
 *   and [amountMinor] equals [toAmountMinor] in the receiving account's currency.
 */
data class BillDraft(
    val type: BillType,
    val occurredAtMs: Long,
    val amountMinor: Long,
    val currencyCode: String,
    val discountMinor: Long = 0,
    val cashAccountId: Long?,
    val accountAmountMinor: Long,
    val rate: BigDecimal = BigDecimal.ONE,
    val rateSource: BillRateSource = BillRateSource.SAME,
    val transferKind: TransferKind? = null,
    val toCashAccountId: Long? = null,
    val toAmountMinor: Long? = null,
    val feeMinor: Long = 0,
    val counterparty: String = "",
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val countInStats: Boolean = true,
    val note: String = "",
) {
    val paidMinor: Long get() = amountMinor - discountMinor
}

data class Bill(
    val id: Long,
    val type: BillType,
    val occurredAtMs: Long,
    val amountMinor: Long,
    val currency: Currency,
    val discountMinor: Long,
    /** Null for an external source and after the sub-account was deleted. */
    val cashAccountId: Long?,
    val accountCurrency: Currency,
    val accountAmountMinor: Long,
    val rate: BigDecimal,
    val rateSource: BillRateSource,
    val transferKind: TransferKind?,
    val toCashAccountId: Long?,
    val toCurrency: Currency?,
    val toAmountMinor: Long?,
    val feeMinor: Long,
    val counterparty: String,
    val categoryId: Long?,
    val subCategoryId: Long?,
    val countInStats: Boolean,
    val reimbursable: Boolean,
    val reimburseState: ReimburseState,
    val parentId: Long?,
    val note: String,
    val revision: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** Its account was deleted; an expense or income with no account and this false was kept on no account. */
    val accountDeleted: Boolean = false,
) {
    val paidMinor: Long get() = amountMinor - discountMinor

    fun toDraft(): BillDraft = BillDraft(type, occurredAtMs, amountMinor, currency.code, discountMinor, cashAccountId,
        accountAmountMinor, rate, rateSource, transferKind, toCashAccountId, toAmountMinor, feeMinor, counterparty,
        categoryId, subCategoryId, countInStats, note)
}

object BillRules {
    const val MAX_NOTE_LENGTH = 2000
    const val MAX_COUNTERPARTY_LENGTH = 40
    const val MAX_CATEGORY_NAME_LENGTH = 20
    /** Rates are kept to as many places as the exchange rate settings allow. */
    const val RATE_SCALE = 12

    /** [minor] of [from] at [rate], in [to]'s minor units, rounded half up. */
    fun convert(minor: Long, from: Currency, to: Currency, rate: BigDecimal): Long {
        val value = BigDecimal.valueOf(minor).movePointLeft(from.fraction_digits).multiply(rate)
            .setScale(to.fraction_digits, RoundingMode.HALF_UP).movePointRight(to.fraction_digits)
        return try { value.longValueExact() } catch (_: ArithmeticException) { throw DomainException(ErrorCode.OVERFLOW) }
    }

    /** The rate that turns [fromMinor] of [from] into [toMinor] of [to], to [RATE_SCALE] places. */
    fun derivedRate(fromMinor: Long, from: Currency, toMinor: Long, to: Currency): BigDecimal {
        if (fromMinor <= 0 || toMinor <= 0) throw DomainException(ErrorCode.POSITIVE)
        return BigDecimal.valueOf(toMinor).movePointLeft(to.fraction_digits)
            .divide(BigDecimal.valueOf(fromMinor).movePointLeft(from.fraction_digits), RATE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
    }

    fun note(value: String): String = value.trim().also {
        if (it.length > MAX_NOTE_LENGTH) throw DomainException(ErrorCode.FORMAT)
    }

    fun counterparty(value: String): String = value.trim().also {
        if (it.codePointCount(0, it.length) > MAX_COUNTERPARTY_LENGTH) throw DomainException(ErrorCode.NAME)
    }

    /** [value] cut to the counterparty limit, for names the app fills in itself. */
    fun counterpartyOrTruncated(value: String): String {
        val trimmed = value.trim()
        val count = trimmed.codePointCount(0, trimmed.length)
        return if (count <= MAX_COUNTERPARTY_LENGTH) trimmed
        else trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_COUNTERPARTY_LENGTH))
    }

    fun categoryName(value: String): String = value.trim().also {
        if (it.codePointCount(0, it.length) !in 1..MAX_CATEGORY_NAME_LENGTH) throw DomainException(ErrorCode.NAME)
    }
}

/** The rate a new bill starts with; the user can always replace it for this bill only. */
object BillRates {
    data class Suggestion(val rate: BigDecimal, val source: BillRateSource)

    /**
     * How much of [to] one [from] buys. With automatic rates on, [dayRates] (that day's stored rates, 1 base = value)
     * come first and the current rates fill in; otherwise the manual rates. Null when either currency has no rate:
     * a bill is never converted 1:1 by default.
     */
    fun suggest(from: Currency, to: Currency, settings: AppSettings, fx: dev.valnook.domain.fx.FxContext,
        dayRates: Map<String, BigDecimal>): Suggestion? {
        if (from == to) return Suggestion(BigDecimal.ONE, BillRateSource.SAME)
        val base = settings.baseCurrency ?: return null
        val resolver = dev.valnook.domain.fx.FxRateResolver(settings, fx)
        fun inBase(code: String): BigDecimal? = when {
            code == base.code -> BigDecimal.ONE
            fx.autoEnabled -> dayRates[code]?.takeIf { it.signum() > 0 }
                ?.let { BigDecimal.ONE.divide(it, java.math.MathContext.DECIMAL128) } ?: resolver.known(code)
            else -> resolver.known(code)
        }
        val fromBase = inBase(from.code) ?: return null
        val toBase = inBase(to.code)?.takeIf { it.signum() > 0 } ?: return null
        val rate = fromBase.divide(toBase, BillRules.RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
        if (rate.signum() <= 0) return null
        return Suggestion(rate, if (fx.autoEnabled) BillRateSource.AUTO else BillRateSource.MANUAL)
    }
}

/** Category colours by key; the app picks the shade for light and dark. Keys are stored, so never rename one. */
object BillColors {
    val keys = listOf("red", "orange", "amber", "green", "teal", "cyan", "blue", "indigo", "purple", "pink", "brown", "grey")
}

/** The categories every database starts with. Keys are stored, so never rename or reuse one. */
object BillCategoryPresets {
    data class Preset(val key: String, val kind: BillCategoryKind, val parentKey: String?, val icon: String,
        val color: String, val chinese: String, val english: String)

    private fun group(kind: BillCategoryKind, key: String, icon: String, color: String, chinese: String, english: String,
        vararg children: Triple<String, String, Pair<String, String>>): List<Preset> =
        listOf(Preset(key, kind, null, icon, color, chinese, english)) +
            children.map { (child, childIcon, names) -> Preset(child, kind, key, childIcon, color, names.first, names.second) }

    private fun c(key: String, icon: String, chinese: String, english: String) = Triple(key, icon, chinese to english)

    private val E = BillCategoryKind.EXPENSE
    private val I = BillCategoryKind.INCOME

    /** Parents first within each group, in display order. */
    val all: List<Preset> = listOf(
        group(E, "food", "utensils", "orange", "餐饮", "Food & drink",
            c("food.breakfast", "mug-hot", "早餐", "Breakfast"), c("food.lunch", "bowl-food", "午餐", "Lunch"),
            c("food.dinner", "bowl-rice", "晚餐", "Dinner"), c("food.snacks", "cookie", "零食", "Snacks"),
            c("food.drinks", "glass-water", "饮料", "Drinks"), c("food.fruit", "apple-whole", "水果", "Fruit"),
            c("food.late_night", "moon", "夜宵", "Late-night snacks"), c("food.delivery", "motorcycle", "外卖", "Delivery"),
            c("food.dining_out", "champagne-glasses", "聚餐", "Eating out together")),
        group(E, "shopping", "cart-shopping", "pink", "购物", "Shopping",
            c("shopping.daily", "pump-soap", "日用品", "Daily necessities"), c("shopping.clothing", "shirt", "服饰鞋包", "Clothing & bags"),
            c("shopping.electronics", "mobile-screen", "数码电子", "Electronics"), c("shopping.home", "couch", "家居家电", "Home & appliances"),
            c("shopping.beauty", "spray-can-sparkles", "美妆个护", "Beauty & personal care")),
        group(E, "transport", "bus", "blue", "交通", "Transport",
            c("transport.transit", "train-subway", "公交地铁", "Bus & metro"), c("transport.taxi", "taxi", "打车", "Taxi"),
            c("transport.bike", "bicycle", "单车", "Bike sharing"), c("transport.rail", "train", "火车高铁", "Train"),
            c("transport.flights", "plane-departure", "机票", "Flights"), c("transport.fuel", "gas-pump", "加油充电", "Fuel & charging"),
            c("transport.parking", "square-parking", "停车过路", "Parking & tolls")),
        group(E, "housing", "house", "brown", "居住", "Housing",
            c("housing.rent", "key", "房租", "Rent"), c("housing.utilities", "bolt", "水电燃气", "Utilities"),
            c("housing.property", "building", "物业", "Property fees"), c("housing.repairs", "screwdriver-wrench", "维修", "Repairs"),
            c("housing.housekeeping", "broom", "家政", "Housekeeping")),
        group(E, "telecom", "phone", "cyan", "通讯", "Phone & internet",
            c("telecom.phone", "sim-card", "话费", "Phone bill"), c("telecom.broadband", "wifi", "宽带", "Broadband")),
        group(E, "entertainment", "ticket", "purple", "娱乐", "Entertainment",
            c("entertainment.games", "gamepad", "游戏", "Games"), c("entertainment.shows", "film", "电影演出", "Films & shows"),
            c("entertainment.fitness", "dumbbell", "运动健身", "Sports & fitness"), c("entertainment.travel", "suitcase-rolling", "旅行", "Travel"),
            c("entertainment.parties", "cake-candles", "聚会", "Parties")),
        group(E, "subscriptions", "repeat", "indigo", "订阅", "Subscriptions",
            c("subscriptions.software", "laptop-code", "软件服务", "Software"), c("subscriptions.media", "circle-play", "影音会员", "Streaming"),
            c("subscriptions.cloud", "cloud", "云存储", "Cloud storage")),
        group(E, "medical", "kit-medical", "red", "医疗", "Medical",
            c("medical.clinic", "user-doctor", "门诊", "Doctor visits"), c("medical.medicine", "pills", "药品", "Medicine"),
            c("medical.checkup", "stethoscope", "体检", "Check-ups"), c("medical.wellness", "heart-pulse", "保健", "Wellness")),
        group(E, "education", "graduation-cap", "teal", "学习", "Education",
            c("education.books", "book", "书籍", "Books"), c("education.courses", "chalkboard-user", "课程", "Courses"),
            c("education.exams", "file-pen", "考试报名", "Exam fees"), c("education.stationery", "pencil", "文具", "Stationery")),
        group(E, "social", "gift", "amber", "人情", "Gifts & social",
            c("social.red_packets", "envelope", "红包礼金", "Red packets"), c("social.gifts", "gift", "礼物", "Presents"),
            c("social.treats", "handshake", "请客", "Treating others"), c("social.elders", "person-cane", "长辈", "Parents & elders")),
        group(E, "business", "briefcase", "grey", "公务", "Work expenses",
            c("business.travel", "suitcase", "差旅", "Business travel"), c("business.supplies", "paperclip", "办公用品", "Office supplies"),
            c("business.hospitality", "mug-saucer", "招待", "Hospitality"), c("business.paid_for_others", "hand-holding-dollar", "代付", "Paid for others")),
        group(E, "finance", "building-columns", "green", "金融", "Finance",
            c("finance.fees", "receipt", "手续费", "Fees"), c("finance.interest", "percent", "利息", "Interest"),
            c("finance.insurance", "shield-halved", "保险", "Insurance"), c("finance.taxes", "file-invoice-dollar", "税费", "Taxes")),
        group(E, "other", "shapes", "grey", "其他", "Other",
            c("other.uncategorized", "circle-question", "未分类", "Uncategorised")),
        group(I, "income.pay", "sack-dollar", "green", "薪酬", "Pay",
            c("income.pay.salary", "money-bill-wave", "工资", "Salary"), c("income.pay.bonus", "award", "奖金", "Bonus"),
            c("income.pay.allowance", "hand-holding-heart", "补贴", "Allowances")),
        group(I, "income.side", "laptop", "blue", "副业", "Side income",
            c("income.side.part_time", "user-clock", "兼职", "Part-time work"), c("income.side.royalties", "pen-nib", "稿酬", "Royalties"),
            c("income.side.resale", "recycle", "二手转卖", "Second-hand sales")),
        group(I, "income.investing", "chart-line", "teal", "理财", "Investing",
            c("income.investing.deposit_interest", "piggy-bank", "存款利息", "Deposit interest"),
            c("income.investing.other", "coins", "其他收益", "Other returns")),
        group(I, "income.social", "gift", "amber", "人情", "Gifts received",
            c("income.social.red_packets", "envelope-open-text", "红包", "Red packets"), c("income.social.gifts", "envelope", "礼金", "Gift money")),
        group(I, "income.other", "shapes", "grey", "其他", "Other",
            c("income.other.tax_refund", "file-invoice", "退税", "Tax refunds"), c("income.other.prizes", "trophy", "中奖", "Prizes"),
            c("income.other.uncategorized", "circle-question", "未分类", "Uncategorised")),
    ).flatten()

    private val byKey = all.associateBy { it.key }

    fun preset(key: String?): Preset? = key?.let(byKey::get)

    fun name(key: String?, english: Boolean): String? = preset(key)?.let { if (english) it.english else it.chinese }
}
