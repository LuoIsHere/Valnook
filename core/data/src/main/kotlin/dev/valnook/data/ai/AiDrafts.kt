package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.ai.AiDraftFlag
import dev.valnook.domain.ai.AiRules
import dev.valnook.domain.ai.AiSettings
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillCategoryPresets
import dev.valnook.domain.model.BillRules
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.Currency
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** What one create_bill_drafts call asked for: its message, and each draft with the label of a draft it replaces. */
data class AiDraftCall(val message: String, val drafts: List<Pair<String?, AiBillDraft>>)

/**
 * Checks the drafts a model wrote against what it was sent. A field that does not hold up is dropped and filled the
 * way the user would expect, never the whole bill: an unknown category falls back to the default one, an unknown
 * account to the category's last one and then the default account, a missing time to now.
 *
 * [categories] and [accounts] are the lists the request sent (hidden categories and unsent accounts are not on them);
 * [allAccounts] are every account, for the fallbacks.
 */
class AiDraftReader(
    private val settings: AiSettings,
    categories: List<BillCategory>,
    private val accounts: List<AiAccountChoice>,
    private val allAccounts: List<AiAccountChoice>,
    private val now: LocalDateTime,
    private val baseCurrency: String?,
    /** Reading a screenshot or a share, where the currency is to be read off it: a missing one is flagged. */
    private val currencyExpected: Boolean = false,
) {
    private val shown = categories.filter { !it.hidden }
    private val byId = categories.associateBy { it.id }
    private val sentAccounts = accounts.map { it.id }.toSet()
    private val knownAccounts = allAccounts.associateBy { it.id }

    /** Reads one call's arguments; throws [IllegalArgumentException] when they are not the tool's shape at all. */
    fun read(arguments: String): AiDraftCall {
        val root = runCatching { AiJson.format.parseToJsonElement(arguments.ifBlank { "{}" }) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("not an object")
        val bills = when (val value = root["bills"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value
            // Some models send the array as a JSON string.
            is JsonPrimitive -> runCatching { AiJson.format.parseToJsonElement(value.content) }.getOrNull() as? JsonArray
                ?: throw IllegalArgumentException("bills not an array")
            else -> throw IllegalArgumentException("bills not an array")
        }
        val message = (root["message"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return AiDraftCall(message, bills.take(AiRules.MAX_DRAFTS).mapNotNull { (it as? JsonObject)?.let(::bill) })
    }

    private fun bill(item: JsonObject): Pair<String?, AiBillDraft> {
        val flags = mutableSetOf<AiDraftFlag>()
        val type = when (text(item, "type")?.uppercase()) {
            "INCOME" -> BillType.INCOME
            "TRANSFER" -> BillType.TRANSFER
            else -> BillType.EXPENSE
        }
        val label = text(item, "draft_id")?.takeIf { it.isNotBlank() }
        val accountId = id(item, "account_id")?.takeIf { it in sentAccounts }
        val toAccountId = id(item, "to_account_id")?.takeIf { it in sentAccounts && it != accountId }
        val time = time(item, flags)
        val note = text(item, "note")?.trim()?.take(BillRules.MAX_NOTE_LENGTH).orEmpty()
        val counterparty = text(item, "counterparty")?.let(BillRules::counterpartyOrTruncated).orEmpty()

        if (type == BillType.TRANSFER) {
            if (accountId == null && toAccountId == null) flags += AiDraftFlag.ACCOUNT
            // A transfer is counted in the currency of the account it leaves, or of the one it reaches from outside.
            val currency = (accountId ?: toAccountId)?.let { knownAccounts[it]?.currencyCode }
                ?: currency(text(item, "currency")) ?: baseCurrency ?: DEFAULT_CURRENCY
            // Between two currencies, an amount said in the receiving one is what arrived.
            val said = currency(text(item, "currency"))
            val toCurrency = toAccountId?.let { knownAccounts[it]?.currencyCode }
            val crossing = accountId != null && toCurrency != null && toCurrency != currency
            if (accountId != null && said != null && said != currency && said == toCurrency) {
                val received = amount(item, "amount", said).takeIf { it != null && it > 0 }
                if (received == null) flags += AiDraftFlag.AMOUNT
                return label to AiBillDraft(type, null, currency, 0, null, null, accountId, toAccountId, time, note,
                    counterparty, false, flags, toAmountMinor = received, rate = rate(item))
            }
            val amount = amount(item, "amount", currency).takeIf { it != null && it > 0 }
            if (amount == null) flags += AiDraftFlag.AMOUNT
            // Between currencies the user may say what arrived as well, or the rate; what arrived wins.
            val received = if (crossing && amount != null) amount(item, "account_amount", toCurrency!!)?.takeIf { it > 0 } else null
            return label to AiBillDraft(type, amount, currency, 0, null, null, accountId, toAccountId, time, note,
                counterparty, false, flags, toAmountMinor = received,
                rate = if (crossing && received == null) rate(item) else null)
        }

        val kind = if (type == BillType.INCOME) BillCategoryKind.INCOME else BillCategoryKind.EXPENSE
        val (category, sub) = category(item, kind) ?: run { flags += AiDraftFlag.CATEGORY; defaultCategory(kind) }
        val account = accountId ?: run {
            if (id(item, "account_id") != null) flags += AiDraftFlag.ACCOUNT
            // As in the bill form: a subcategory's own last account, or a top-level category's.
            (if (sub == null) remembered(category) else remembered(sub))
                ?: settings.defaultCashAccountId?.takeIf { it in knownAccounts }
                ?.also { flags += AiDraftFlag.ACCOUNT }
        }
        val said = currency(text(item, "currency"))
        if (said == null && currencyExpected) flags += AiDraftFlag.CURRENCY
        val currency = said ?: account?.let { knownAccounts[it]?.currencyCode } ?: baseCurrency ?: DEFAULT_CURRENCY
        val amount = amount(item, "amount", currency).takeIf { it != null && it > 0 }
        if (amount == null) flags += AiDraftFlag.AMOUNT
        val discount = amount(item, "discount", currency)?.takeIf { it > 0 && amount != null && it < amount } ?: 0
        val reimbursable = type == BillType.EXPENSE && bool(item, "reimbursable")
        // Paid in another currency than the account's: a rate or charged amount the user gave; the amount wins.
        val accountCurrency = account?.let { knownAccounts[it]?.currencyCode }
        val crossing = accountCurrency != null && accountCurrency != currency
        val charged = if (crossing && amount != null) amount(item, "account_amount", accountCurrency!!)?.takeIf { it > 0 } else null
        return label to AiBillDraft(type, amount, currency, discount, category, sub, account, null, time, note,
            counterparty, reimbursable, flags, rate = if (crossing && charged == null) rate(item) else null,
            accountAmountMinor = charged)
    }

    /** The category and subcategory named, if they are shown ones of [kind]; a subcategory alone brings its parent. */
    private fun category(item: JsonObject, kind: BillCategoryKind): Pair<Long, Long?>? {
        fun usable(id: Long?) = id?.let(byId::get)?.takeIf { it.kind == kind && !it.hidden && it in shown }
        val named = usable(id(item, "category_id"))
        val sub = usable(id(item, "sub_category_id"))?.takeIf { it.parentId != null }
        return when {
            sub != null && (named == null || named.id == sub.parentId) -> usable(sub.parentId)?.let { it.id to sub.id }
            named == null -> null
            named.parentId != null -> usable(named.parentId)?.let { it.id to named.id }
            else -> named.id to null
        }
    }

    /** The default category of [kind] from the settings, else the catch-all one the app always keeps. */
    private fun defaultCategory(kind: BillCategoryKind): Pair<Long?, Long?> {
        val chosen = (if (kind == BillCategoryKind.EXPENSE) settings.defaultExpenseCategoryId else settings.defaultIncomeCategoryId)
            ?.let(byId::get)?.takeIf { it.kind == kind && !it.hidden }
        if (chosen != null) return if (chosen.parentId == null) chosen.id to null else chosen.parentId to chosen.id
        val (parentKey, subKey) = BillCategoryPresets.fallback(kind)
        val parent = byId.values.firstOrNull { it.kind == kind && it.parentId == null && it.builtinKey == parentKey }
        val sub = subKey?.let { key -> byId.values.firstOrNull { it.parentId == parent?.id && it.builtinKey == key } }
        // Without the preset (renamed keys stay; only a broken list lacks it) the first shown category of the kind.
        return if (parent != null) parent.id to sub?.id
            else shown.filter { it.kind == kind && it.parentId == null }.minByOrNull { it.sortOrder }?.id to null
    }

    private fun remembered(categoryId: Long?): Long? =
        categoryId?.let(byId::get)?.lastCashAccountId?.takeIf { it in knownAccounts }

    private fun time(item: JsonObject, flags: MutableSet<AiDraftFlag>): LocalDateTime {
        val raw = text(item, "occurred_at")?.trim()?.takeIf { it.isNotEmpty() } ?: return now
        val value = parseTime(raw)
        if (value == null || kotlin.math.abs(ChronoUnit.DAYS.between(now, value)) > AiRules.MAX_TIME_DAYS) {
            flags += AiDraftFlag.TIME
            return now
        }
        return value
    }

    private fun parseTime(raw: String): LocalDateTime? {
        val text = raw.replace(' ', 'T').removeSuffix("Z")
        if (text.length == 10) return runCatching { LocalDate.parse(text) }.getOrNull()
            ?.atTime(LocalTime.of(settings.defaultTimeMinutes / 60, settings.defaultTimeMinutes % 60))
        return runCatching { LocalDateTime.parse(text.take(19)) }.getOrNull()?.withSecond(0)?.withNano(0)
            ?: runCatching { LocalDateTime.parse(text.take(16)) }.getOrNull()
    }

    private fun currency(code: String?): String? =
        code?.trim()?.uppercase()?.takeIf { c -> Currency.supported.any { it.code == c } }

    /** A decimal amount in [currency]'s minor units, rounded to its places; null when it is not a number. */
    private fun amount(item: JsonObject, key: String, currency: String): Long? {
        val value = (item[key] as? JsonPrimitive)?.contentOrNull?.trim()?.replace(",", "")?.toBigDecimalOrNull() ?: return null
        val digits = Currency.supported.firstOrNull { it.code == currency }?.fraction_digits ?: 2
        return runCatching { value.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).longValueExact() }.getOrNull()
            ?.takeIf { it < MAX_MINOR }
    }

    /** A rate the user gave: a positive number within reason, else nothing. */
    private fun rate(item: JsonObject): BigDecimal? =
        (item["rate"] as? JsonPrimitive)?.contentOrNull?.trim()?.replace(",", "")?.toBigDecimalOrNull()
            ?.takeIf { it.signum() > 0 && it < MAX_RATE }?.stripTrailingZeros()

    private fun text(item: JsonObject, key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull

    private fun id(item: JsonObject, key: String): Long? = (item[key] as? JsonPrimitive)?.contentOrNull?.trim()
        ?.let { it.toLongOrNull() ?: it.toBigDecimalOrNull()?.takeIf { d -> d.stripTrailingZeros().scale() <= 0 }?.toLong() }

    private fun bool(item: JsonObject, key: String): Boolean = (item[key] as? JsonPrimitive)?.let {
        it.booleanOrNull ?: (it.contentOrNull?.lowercase() == "true")
    } ?: false

    private companion object {
        const val DEFAULT_CURRENCY = "CNY"
        /** Far above any real bill, so a stray number never overflows the arithmetic after it. */
        val MAX_MINOR: Long = BigDecimal("1e15").toLong()
        val MAX_RATE: BigDecimal = BigDecimal("1e9")
    }
}
