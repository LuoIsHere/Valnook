package dev.valnook.domain.model

data class Currency(val code: String, val fraction_digits: Int, val name: String = code,
    val english_name: String = code) {
    /** The name for an interface in [language] (an ISO 639 code): Chinese for zh, otherwise English. */
    fun display_name(language: String): String = if (language == "zh") name else english_name

    companion object {
        val supported = listOf(
            Currency("CNY", 2, "人民币", "Chinese Yuan"),
            Currency("USD", 2, "美元", "US Dollar"),
            Currency("HKD", 2, "港币", "Hong Kong Dollar"),
            Currency("EUR", 2, "欧元", "Euro"),
            Currency("GBP", 2, "英镑", "British Pound"),
            Currency("JPY", 0, "日元", "Japanese Yen"),
            Currency("SGD", 2, "新加坡元", "Singapore Dollar"),
            Currency("AUD", 2, "澳大利亚元", "Australian Dollar"),
            Currency("CAD", 2, "加拿大元", "Canadian Dollar"),
            Currency("CHF", 2, "瑞士法郎", "Swiss Franc"),
            Currency("NZD", 2, "新西兰元", "New Zealand Dollar"),
            Currency("KRW", 0, "韩元", "South Korean Won"),
            Currency("TWD", 2, "新台币", "New Taiwan Dollar"),
            Currency("MOP", 2, "澳门元", "Macanese Pataca"),
            Currency("THB", 2, "泰铢", "Thai Baht"),
            Currency("MYR", 2, "马来西亚林吉特", "Malaysian Ringgit"),
            Currency("IDR", 2, "印度尼西亚卢比", "Indonesian Rupiah"),
            Currency("PHP", 2, "菲律宾比索", "Philippine Peso"),
            Currency("VND", 0, "越南盾", "Vietnamese Dong"),
            Currency("INR", 2, "印度卢比", "Indian Rupee"),
            Currency("PKR", 2, "巴基斯坦卢比", "Pakistani Rupee"),
            Currency("LKR", 2, "斯里兰卡卢比", "Sri Lankan Rupee"),
            Currency("BDT", 2, "孟加拉塔卡", "Bangladeshi Taka"),
            Currency("NPR", 2, "尼泊尔卢比", "Nepalese Rupee"),
            Currency("KWD", 3, "科威特第纳尔", "Kuwaiti Dinar"),
            Currency("BHD", 3, "巴林第纳尔", "Bahraini Dinar"),
            Currency("OMR", 3, "阿曼里亚尔", "Omani Rial"),
            Currency("AED", 2, "阿联酋迪拉姆", "UAE Dirham"),
            Currency("SAR", 2, "沙特里亚尔", "Saudi Riyal"),
            Currency("QAR", 2, "卡塔尔里亚尔", "Qatari Riyal"),
            Currency("ILS", 2, "以色列新谢克尔", "Israeli New Shekel"),
            Currency("TRY", 2, "土耳其里拉", "Turkish Lira"),
            Currency("EGP", 2, "埃及镑", "Egyptian Pound"),
            Currency("ZAR", 2, "南非兰特", "South African Rand"),
            Currency("NGN", 2, "尼日利亚奈拉", "Nigerian Naira"),
            Currency("KES", 2, "肯尼亚先令", "Kenyan Shilling"),
            Currency("MAD", 2, "摩洛哥迪拉姆", "Moroccan Dirham"),
            Currency("BRL", 2, "巴西雷亚尔", "Brazilian Real"),
            Currency("MXN", 2, "墨西哥比索", "Mexican Peso"),
            Currency("ARS", 2, "阿根廷比索", "Argentine Peso"),
            Currency("CLP", 0, "智利比索", "Chilean Peso"),
            Currency("COP", 2, "哥伦比亚比索", "Colombian Peso"),
            Currency("PEN", 2, "秘鲁索尔", "Peruvian Sol"),
            Currency("RUB", 2, "俄罗斯卢布", "Russian Ruble"),
            Currency("PLN", 2, "波兰兹罗提", "Polish Zloty"),
            Currency("CZK", 2, "捷克克朗", "Czech Koruna"),
            Currency("HUF", 2, "匈牙利福林", "Hungarian Forint"),
            Currency("SEK", 2, "瑞典克朗", "Swedish Krona"),
            Currency("NOK", 2, "挪威克朗", "Norwegian Krone"),
            Currency("DKK", 2, "丹麦克朗", "Danish Krone"),
            Currency("RON", 2, "罗马尼亚列伊", "Romanian Leu"),
            Currency("ISK", 0, "冰岛克朗", "Icelandic Krona"),
            Currency("UAH", 2, "乌克兰格里夫纳", "Ukrainian Hryvnia"),
            Currency("TND", 3, "突尼斯第纳尔", "Tunisian Dinar")
        )
        /** Code and name of every supported currency, for a picker in [language]. */
        fun options(language: String): List<Pair<String, String>> = supported.map { it.code to it.display_name(language) }
        fun of(code: String): Currency = supported.firstOrNull { it.code == code.trim().uppercase() }
            ?: throw DomainException(ErrorCode.CURRENCY)
    }
}
enum class ErrorCode { CURRENCY, FORMAT, PRECISION, OVERFLOW, POSITIVE, DATE, INSUFFICIENT_CASH,
    INSUFFICIENT_HOLDING, STALE_BALANCE, ALREADY_CLOSED, NOT_MATURED, OPERATION_CONFLICT,
    NOT_FOUND, NAME, AMOUNT_TOO_SMALL, DUPLICATE_TYPE, STALE_RECORD, SOURCE_RECORD,
    CURRENCY_LOCKED, SYMBOL_LOCKED, PRICE_CONFIRMATION, HISTORY_CONFLICT, DUPLICATE_CURRENCY,
    WRONG_CASH_ACCOUNT, CASH_ACCOUNT_IN_USE, SESSION_EXPIRED, WEB_ADMIN_ACTIVE, MAINTENANCE_ACTIVE,
    INVALID_ACCOUNT_TYPE, INVALID_CREDIT_LIMIT, INVALID_STATEMENT_DAY, INVALID_DUE_RULE,
    CREDIT_SOURCE_INVALID, CREDIT_SOURCE_CURRENCY, CREDIT_SOURCE_PARENT, CREDIT_SOURCE_CHAIN, CREDIT_SOURCE_CYCLE,
    CREDIT_LIMIT_IN_USE, BALANCE_ACCOUNT_IN_USE, WALLET_FACE_IN_USE, WALLET_FACE_DUPLICATE, WALLET_FACE_LIBRARY_FULL,
    DEFAULT_TYPE, CATEGORY_PROTECTED, CATEGORY_HAS_SUBCATEGORIES }
class DomainException(val code: ErrorCode) : IllegalArgumentException(code.name)
data class SavingsAccount(val id: Long, val name: String, val note: String, val revision: Long = 1, val icon: AccountIcon = AccountIcon(),
    val showDepositSummary: Boolean = true, val showInvestmentSummary: Boolean = true,
    val groupId: Long = AccountGroup.DEFAULT_ID)
data class CashAccount(val account_id: Long, val currency: Currency, val balance_minor: Long, val revision: Long,
    val id: Long = 0, val name: String = "", val note: String = "", val currencyLocked: Boolean = true,
    val creditProfile: CreditAccountProfile? = null, val includeInAvailableCash: Boolean = true,
    val showOnAccountsPage: Boolean = true) {
    val type: BalanceAccountType get() = if (creditProfile == null) BalanceAccountType.SAVINGS else BalanceAccountType.CREDIT
}
typealias CashBalance = CashAccount
data class TermDeposit(val id: Long, val account_id: Long, val currency: Currency,
    val principal_minor: Long, val annual_rate_percent_e8: Long, val start_epoch_day: Long,
    val end_epoch_day: Long, val expected_interest_minor: Long, val closed: Boolean,
    val open_cash_linked: Boolean, val close_cash_linked: Boolean?, val revision: Long = 1,
    val openCashAccountId: Long? = null, val closeCashAccountId: Long? = null, val closedAtMs: Long? = null)
/** [isDefault]: the built-in "uncategorized" class, which cannot be renamed or deleted. */
data class AssetType(val id: Long, val name: String, val isDefault: Boolean = false)

object AssetTypeRules {
    /** Stored as the default class's normalized name; never produced from a name the user may save. */
    const val DEFAULT_KEY = "~default"
    /** Stored as the default class's name, and shown in its place by versions that do not know it. */
    const val DEFAULT_NAME = "未分类"
    /** Normalized names no other class may take, so the default never appears twice. */
    val RESERVED_NAMES = setOf(DEFAULT_KEY, DEFAULT_NAME, "uncategorized")

    /** Names are unique and the default's cannot change, so its stored name identifies it wherever only the name is at hand. */
    fun isDefaultName(name: String): Boolean = name == DEFAULT_NAME
}
data class Investment(val id: Long, val account_id: Long, val type_id: Long, val type_name: String,
    val name: String, val symbol: String, val currency: Currency, val holding_quantity_e8: Long,
    val current_price_e8: Long, val price_updated_at_ms: Long,
    val revision: Long = 1, val last_activity_at_ms: Long = 0, val instrumentId: Long = id,
    val remainingCost: String? = null, val realizedProfit: String? = null,
    val chronologyValid: Boolean = true,
    val algorithmVersion: Int = dev.valnook.domain.calculation.InvestmentProfitCalculator.ALGORITHM_VERSION,
    val isClosed: Boolean = false)
/** [DIVIDEND]: income from a holding; it moves no units and adds to realized profit. */
enum class Direction { BUY, SELL, DIVIDEND }
/** One recorded price of an instrument, as the price trend draws it. */
data class InstrumentPricePoint(val atMs: Long, val priceE5: Long)
data class Trade(val id: Long, val investment_id: Long, val direction: Direction,
    val quantity_e8: Long, val execution_price_e8: Long, val amount_minor: Long,
    val currency: Currency, val cash_linked: Boolean, val occurred_at_ms: Long, val revision: Long = 1,
    val cashAccountId: Long? = null, val fee_minor: Long = 0)

sealed interface CashLinkSelection {
    data object None : CashLinkSelection
    data class Selected(val cashAccountId: Long) : CashLinkSelection
}

/**
 * [TRADE_REMOVED] / [DIVIDEND_REMOVED]: cash a trade or dividend moved before its instrument was deleted; it no
 * longer points at the record.
 *
 * [BILL] is a bill's own side (an expense, income, refund or a transfer's source); [BILL_TRANSFER_IN] a transfer's
 * receiving side. [BILL_REMOVED]: cash a bill moved before it was deleted along with its category; the balance keeps
 * it, the entry's note names the bill, and it no longer points at the record.
 */
enum class CashSource { CASH_SET, TRADE, TERM_OPEN, TERM_CLOSE, TRADE_REMOVED, DIVIDEND, DIVIDEND_REMOVED, BILL, BILL_TRANSFER_IN,
    BILL_REMOVED }

/**
 * [balanceAfterMinor] is the sub-account's balance right after this entry, worked out from the current balance and the
 * entries after it when a month page is read; null where a query does not work it out.
 */
data class CashEntry(val id: Long, val account_id: Long, val currency: Currency,
    val delta_minor: Long, val occurred_at_ms: Long, val source: CashSource,
    val source_id: Long?, val investment_id: Long?, val note: String, val revision: Long,
    val cashAccountId: Long = 0, val cashAccountName: String = "", val balanceAfterMinor: Long? = null,
    /** The bill behind a BILL or BILL_TRANSFER_IN entry, where the ledger reads it; null elsewhere. */
    val bill: CashEntryBill? = null) {
    val editable: Boolean get() = source == CashSource.CASH_SET
}
data class TradeCursor(val occurred_at_ms: Long, val id: Long)
data class OperationResult(val kind: String, val id: Long)
