package dev.valnook.domain.model

data class Currency(val code: String, val fraction_digits: Int, val name: String = code) {
    companion object {
        val supported = listOf(
            Currency("CNY", 2, "人民币"),
            Currency("USD", 2, "美元"),
            Currency("HKD", 2, "港币"),
            Currency("EUR", 2, "欧元"),
            Currency("GBP", 2, "英镑"),
            Currency("JPY", 0, "日元"),
            Currency("SGD", 2, "新加坡元"),
            Currency("AUD", 2, "澳大利亚元"),
            Currency("CAD", 2, "加拿大元"),
            Currency("CHF", 2, "瑞士法郎"),
            Currency("NZD", 2, "新西兰元"),
            Currency("KRW", 0, "韩元"),
            Currency("TWD", 2, "新台币"),
            Currency("MOP", 2, "澳门元"),
            Currency("THB", 2, "泰铢"),
            Currency("MYR", 2, "马来西亚林吉特"),
            Currency("IDR", 2, "印度尼西亚卢比"),
            Currency("PHP", 2, "菲律宾比索"),
            Currency("VND", 0, "越南盾"),
            Currency("INR", 2, "印度卢比"),
            Currency("PKR", 2, "巴基斯坦卢比"),
            Currency("LKR", 2, "斯里兰卡卢比"),
            Currency("BDT", 2, "孟加拉塔卡"),
            Currency("NPR", 2, "尼泊尔卢比"),
            Currency("KWD", 3, "科威特第纳尔"),
            Currency("BHD", 3, "巴林第纳尔"),
            Currency("OMR", 3, "阿曼里亚尔"),
            Currency("AED", 2, "阿联酋迪拉姆"),
            Currency("SAR", 2, "沙特里亚尔"),
            Currency("QAR", 2, "卡塔尔里亚尔"),
            Currency("ILS", 2, "以色列新谢克尔"),
            Currency("TRY", 2, "土耳其里拉"),
            Currency("EGP", 2, "埃及镑"),
            Currency("ZAR", 2, "南非兰特"),
            Currency("NGN", 2, "尼日利亚奈拉"),
            Currency("KES", 2, "肯尼亚先令"),
            Currency("MAD", 2, "摩洛哥迪拉姆"),
            Currency("BRL", 2, "巴西雷亚尔"),
            Currency("MXN", 2, "墨西哥比索"),
            Currency("ARS", 2, "阿根廷比索"),
            Currency("CLP", 0, "智利比索"),
            Currency("COP", 2, "哥伦比亚比索"),
            Currency("PEN", 2, "秘鲁索尔"),
            Currency("RUB", 2, "俄罗斯卢布"),
            Currency("PLN", 2, "波兰兹罗提"),
            Currency("CZK", 2, "捷克克朗"),
            Currency("HUF", 2, "匈牙利福林"),
            Currency("SEK", 2, "瑞典克朗"),
            Currency("NOK", 2, "挪威克朗"),
            Currency("DKK", 2, "丹麦克朗"),
            Currency("RON", 2, "罗马尼亚列伊"),
            Currency("ISK", 0, "冰岛克朗"),
            Currency("UAH", 2, "乌克兰格里夫纳"),
            Currency("TND", 3, "突尼斯第纳尔")
        )
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
    CREDIT_LIMIT_IN_USE, BALANCE_ACCOUNT_IN_USE }
class DomainException(val code: ErrorCode) : IllegalArgumentException(code.name)
data class SavingsAccount(val id: Long, val name: String, val note: String, val revision: Long = 1, val icon: AccountIcon = AccountIcon(),
    val showDepositSummary: Boolean = true, val showInvestmentSummary: Boolean = true)
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
data class AssetType(val id: Long, val name: String)
data class Investment(val id: Long, val account_id: Long, val type_id: Long, val type_name: String,
    val name: String, val symbol: String, val currency: Currency, val holding_quantity_e8: Long,
    val current_price_e8: Long, val price_updated_at_ms: Long,
    val revision: Long = 1, val last_activity_at_ms: Long = 0, val instrumentId: Long = id,
    val remainingCost: String? = null, val realizedProfit: String? = null,
    val chronologyValid: Boolean = true, val algorithmVersion: Int = 4)
enum class Direction { BUY, SELL }
data class Trade(val id: Long, val investment_id: Long, val direction: Direction,
    val quantity_e8: Long, val execution_price_e8: Long, val amount_minor: Long,
    val currency: Currency, val cash_linked: Boolean, val occurred_at_ms: Long, val revision: Long = 1,
    val cashAccountId: Long? = null, val fee_minor: Long = 0)

sealed interface CashLinkSelection {
    data object None : CashLinkSelection
    data class Selected(val cashAccountId: Long) : CashLinkSelection
}

enum class CashSource { CASH_SET, TRADE, TERM_OPEN, TERM_CLOSE }

data class CashEntry(val id: Long, val account_id: Long, val currency: Currency,
    val delta_minor: Long, val occurred_at_ms: Long, val source: CashSource,
    val source_id: Long?, val investment_id: Long?, val note: String, val revision: Long,
    val cashAccountId: Long = 0, val cashAccountName: String = "") {
    val editable: Boolean get() = source == CashSource.CASH_SET
}
data class TradeCursor(val occurred_at_ms: Long, val id: Long)
data class OperationResult(val kind: String, val id: Long)
