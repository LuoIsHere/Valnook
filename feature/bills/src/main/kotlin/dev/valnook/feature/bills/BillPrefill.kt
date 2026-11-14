package dev.valnook.feature.bills

import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A new bill filled in elsewhere (an AI draft, something shared): as a form to open the bill page with, or saved
 * straight away the same way that page saves.
 */
object BillPrefill {
    /**
     * The form for these fields. [currencyCode] is what was paid in; it is kept only where it differs from the
     * account's (or, with no account, the base) currency, as the page keeps it. A transfer without [cashAccountId]
     * comes from outside and one without [toCashAccountId] goes outside; without either it waits for an account.
     */
    fun form(
        type: BillType,
        amountMinor: Long?,
        currencyCode: String?,
        occurredAt: LocalDateTime,
        accounts: List<BillAccountChoice>,
        baseCurrency: String?,
        discountMinor: Long = 0,
        cashAccountId: Long? = null,
        toCashAccountId: Long? = null,
        categoryId: Long? = null,
        subCategoryId: Long? = null,
        note: String = "",
        counterparty: String = "",
        reimbursable: Boolean = false,
        images: List<BillImage> = emptyList(),
        /** A transfer between currencies said by what arrived, in the receiving account's currency. */
        toAmountMinor: Long? = null,
        /** Between currencies, a rate given for this bill: 1 paid (or leaving) unit in the account's (arriving) currency. */
        rate: BigDecimal? = null,
        /** Between currencies, what the account was actually charged or credited, in its currency (expense and income). */
        accountAmountMinor: Long? = null,
    ): BillForm {
        val own = accounts.firstOrNull { it.cashAccountId == cashAccountId }
        val tab = when (type) { BillType.INCOME -> BillTab.INCOME; BillType.TRANSFER -> BillTab.TRANSFER; else -> BillTab.EXPENSE }
        val time = occurredAt.withSecond(0).withNano(0)
        val text = note.take(BillRules.MAX_NOTE_LENGTH)
        if (tab == BillTab.TRANSFER) {
            val target = accounts.firstOrNull { it.cashAccountId == toCashAccountId }
            val source = own ?: target
            val digits = source?.currency?.fraction_digits ?: 2
            val outside = own == null || target == null
            val crossing = own != null && target != null && own.currency != target.currency
            // Only what arrived is known: what left follows from it and the rate. Both known: the rate follows them.
            val received = toAmountMinor?.takeIf { it > 0 && crossing }
            val given = rate?.takeIf { crossing && it.signum() > 0 && !(received != null && amountMinor != null) }
            return BillForm(tab = tab, amount = amount(amountMinor, digits), cashAccountId = own?.cashAccountId,
                toAmount = amount(received, target?.currency?.fraction_digits ?: 2), rate = given?.toPlainString(),
                transferEdits = when {
                    received != null && amountMinor == null -> listOf(TransferFigure.OUT, TransferFigure.RATE, TransferFigure.IN)
                    received != null -> listOf(TransferFigure.RATE, TransferFigure.IN, TransferFigure.OUT)
                    else -> listOf(TransferFigure.IN, TransferFigure.RATE, TransferFigure.OUT)
                },
                accountChosen = own != null, fromExternal = own == null && target != null, toCashAccountId = target?.cashAccountId,
                toExternal = own != null && target == null,
                counterparty = if (outside) BillRules.counterpartyOrTruncated(counterparty) else "",
                occurredAt = time, countInStats = false, note = text, images = images)
        }
        val following = own?.currency?.code ?: baseCurrency
        val currency = currencyCode?.takeIf { code -> code != following && Currency.supported.any { it.code == code } }
        val digits = (currency?.let(Currency::of) ?: own?.currency ?: baseCurrency?.let(Currency::of))?.fraction_digits ?: 2
        val payBack = tab == BillTab.EXPENSE && reimbursable
        // A charged amount or rate given across currencies, as if typed on the page (the amount wins, as there).
        val crossing = currency != null && own != null
        val charged = accountAmountMinor?.takeIf { crossing && it > 0 }?.let { BillExpression.of(it, own!!.currency.fraction_digits) }
        val given = rate?.takeIf { crossing && charged == null && it.signum() > 0 }?.toPlainString()
        return BillForm(tab = tab, amount = amount(amountMinor, digits), rate = given, accountAmount = charged,
            discount = if (discountMinor > 0) BillExpression.of(discountMinor, digits) else BillExpression(),
            currencyCode = currency, cashAccountId = own?.cashAccountId, accountChosen = own != null,
            expenseCategoryId = if (tab == BillTab.EXPENSE) categoryId else null,
            expenseSubId = if (tab == BillTab.EXPENSE) subCategoryId else null,
            incomeCategoryId = if (tab == BillTab.INCOME) categoryId else null,
            incomeSubId = if (tab == BillTab.INCOME) subCategoryId else null,
            occurredAt = time, countInStats = !payBack, note = text, reimbursable = payBack,
            payer = if (payBack) BillRules.counterpartyOrTruncated(counterparty) else "",
            countedBeforeReimbursable = if (payBack) true else null, images = images)
    }

    /** [form] as the bill page's navigation argument. */
    fun encode(form: BillForm): String = BillEditorViewModel.encode(form)

    /**
     * The bill [form] saves as, worked out exactly as the bill page does; null when the page would not save it yet
     * (no amount, no category, no rate between currencies…), so it has to be opened there instead. A transfer between
     * currencies receives the amount the suggested rate gives.
     */
    fun draft(form: BillForm, accounts: List<BillAccountChoice>, settings: AppSettings, fx: FxContext,
        dayRates: Map<String, BigDecimal>, zone: ZoneId): BillDraft? {
        val (f, preview) = complete(form, accounts, settings, fx, dayRates)
        if (preview.problem != null) return null
        return runCatching { BillEditorViewModel.draftOf(f, preview, zone) }.getOrNull()
    }

    /**
     * [form] with the side of a transfer between currencies that was not given worked out at the suggested rate, and
     * its preview: what the bill page would show, and what [draft] saves.
     */
    fun complete(form: BillForm, accounts: List<BillAccountChoice>, settings: AppSettings, fx: FxContext,
        dayRates: Map<String, BigDecimal>): Pair<BillForm, BillPreview> {
        val preview = BillEditorViewModel.preview(form, accounts, settings, fx, dayRates)
        if (form.tab != BillTab.TRANSFER || !preview.crossCurrency) return form to preview
        val from = preview.accountCurrency ?: return form to preview
        val to = preview.toCurrency ?: return form to preview
        val rate = preview.rate?.takeIf { it.signum() > 0 } ?: return form to preview
        val out = form.amount.minor(from.fraction_digits)
        val received = form.toAmount.minor(to.fraction_digits)
        val filled = when {
            form.toAmount.isEmpty && out != null && out > 0 -> runCatching { BillRules.convert(out, from, to, rate) }.getOrNull()
                ?.let { form.copy(toAmount = BillExpression.of(it, to.fraction_digits)) }
            form.amount.isEmpty && received != null && received > 0 -> runCatching {
                BillRules.convert(received, to, from, BigDecimal.ONE.divide(rate, 24, java.math.RoundingMode.HALF_UP))
            }.getOrNull()?.let { form.copy(amount = BillExpression.of(it, from.fraction_digits)) }
            else -> null
        } ?: return form to preview
        return filled to BillEditorViewModel.preview(filled, accounts, settings, fx, dayRates)
    }

    private fun amount(minor: Long?, digits: Int) =
        if (minor != null && minor > 0) BillExpression.of(minor, digits) else BillExpression()
}
