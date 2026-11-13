package dev.valnook.feature.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class BillTab { EXPENSE, INCOME, TRANSFER }

/** What the keypad types into. */
enum class BillField { AMOUNT, DISCOUNT, FEE, TO_AMOUNT, RATE, ACCOUNT_AMOUNT }

/** The discount box takes either the discount or what was actually paid. */
enum class DiscountMode { DISCOUNT, PAID }

/** The three linked figures of a transfer between currencies; the one changed longest ago follows the others. */
enum class TransferFigure { OUT, IN, RATE }

/**
 * Everything the form holds. Amounts are keypad expressions; a rate is typed in the direction shown and kept as
 * text. Categories are kept per tab so switching tabs loses nothing.
 */
data class BillForm(
    val tab: BillTab = BillTab.EXPENSE,
    val amount: BillExpression = BillExpression(),
    val discount: BillExpression = BillExpression(),
    val discountMode: DiscountMode = DiscountMode.DISCOUNT,
    /** The currency paid in; null follows the account's. */
    val currencyCode: String? = null,
    val cashAccountId: Long? = null,
    /** Set once the user picks the account, so a category's remembered account no longer replaces it. */
    val accountChosen: Boolean = false,
    val fromExternal: Boolean = false,
    val toCashAccountId: Long? = null,
    val toExternal: Boolean = false,
    val counterparty: String = "",
    val fee: BillExpression = BillExpression(),
    val toAmount: BillExpression = BillExpression(),
    /** A rate typed for this bill (source to destination); null uses the suggested one. */
    val rate: String? = null,
    /** Whether the rate is typed and shown the other way round (1 destination = ? source). */
    val rateInverted: Boolean = false,
    /** The amount the account actually moved, typed instead of a rate (expense and income). */
    val accountAmount: BillExpression? = null,
    val transferEdits: List<TransferFigure> = listOf(TransferFigure.IN, TransferFigure.RATE, TransferFigure.OUT),
    val expenseCategoryId: Long? = null,
    val expenseSubId: Long? = null,
    val incomeCategoryId: Long? = null,
    val incomeSubId: Long? = null,
    val occurredAt: LocalDateTime,
    val countInStats: Boolean = true,
    val note: String = "",
    val target: BillField = BillField.AMOUNT,
) {
    val categoryId: Long? get() = if (tab == BillTab.INCOME) incomeCategoryId else expenseCategoryId
    val subCategoryId: Long? get() = if (tab == BillTab.INCOME) incomeSubId else expenseSubId
}

/** What the form works out from [BillForm], for the screen and for saving. */
data class BillPreview(
    val currency: Currency?,
    val accountCurrency: Currency?,
    val toCurrency: Currency?,
    val amountMinor: Long?,
    val discountMinor: Long,
    val paidMinor: Long?,
    /** What the own account moves (expense and income), or the received amount (transfer). */
    val convertedMinor: Long?,
    val rate: BigDecimal?,
    val rateSource: BillRateSource?,
    val crossCurrency: Boolean,
    val missingRate: Boolean,
    val problem: BillProblem?,
)

enum class BillProblem { AMOUNT, DISCOUNT, ACCOUNT, CATEGORY, RATE, SAME_ACCOUNT, BOTH_EXTERNAL, DIVIDE }

enum class BillSavePhase { IDLE, WORKING, UNKNOWN, SAVED }

data class BillEditorState(
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val editing: Boolean = false,
    val form: BillForm,
    val preview: BillPreview,
    val categories: List<BillCategory> = emptyList(),
    val accounts: List<BillAccountChoice> = emptyList(),
    val recentNotes: List<String> = emptyList(),
    val baseCurrency: Currency? = null,
    val autoRates: Boolean = false,
    val phase: BillSavePhase = BillSavePhase.IDLE,
    val error: ErrorCode? = null,
    /** Bumps on each save that keeps the page open ("record another"), for the page to acknowledge it. */
    val savedCount: Int = 0,
    /** Confirm was pressed while something was missing: the page says what. */
    val attempted: Boolean = false,
)

/**
 * The bill form. A new bill when [billId] and [copyFromId] are null; [billId] edits one; [copyFromId] starts a new one
 * from another bill's category, account, amount and note, dated now.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillEditorViewModel(
    private val billId: Long?,
    private val copyFromId: Long?,
    initialTab: BillTab,
    private val bills: BillRepository,
    private val commands: FinancialCommands,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private var operationId: String = saved.get<String>("operation") ?: newOperation()
    private val form = MutableStateFlow(saved.get<String>("form")?.let(::decode) ?: BillForm(tab = initialTab, occurredAt = now()))
    private val meta = MutableStateFlow(Meta(
        loaded = saved.get<Boolean>("loaded") ?: (billId == null && copyFromId == null),
        revision = saved.get<Long>("revision"),
        phase = saved.get<String>("phase")?.let(BillSavePhase::valueOf)?.let { if (it == BillSavePhase.WORKING) BillSavePhase.UNKNOWN else it }
            ?: BillSavePhase.IDLE))
    private val dayRates = MutableStateFlow<Map<String, BigDecimal>>(emptyMap())
    private val settingsState = settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val fxState = fx.stateIn(viewModelScope, SharingStarted.Eagerly, FxContext())

    private data class Meta(val loaded: Boolean, val revision: Long?, val failed: Boolean = false,
        val phase: BillSavePhase = BillSavePhase.IDLE, val error: ErrorCode? = null, val savedCount: Int = 0,
        val attempted: Boolean = false)

    private val categories = bills.observeCategories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val accounts = bills.observeAccountChoices().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val notes = form.map { it.categoryId to it.subCategoryId }.distinctUntilChanged()
        .flatMapLatest { (category, sub) -> if (category == null) flowOf(emptyList()) else bills.observeRecentNotes(category, sub) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<BillEditorState> = combine(
        combine(form, meta, categories, accounts, notes) { f, m, c, a, n -> listOf(f, m, c, a, n) },
        settingsState, fxState, dayRates) { parts, settingsValue, fxValue, day ->
        @Suppress("UNCHECKED_CAST") val f = parts[0] as BillForm
        val m = parts[1] as Meta
        @Suppress("UNCHECKED_CAST") val c = parts[2] as List<BillCategory>
        @Suppress("UNCHECKED_CAST") val a = parts[3] as List<BillAccountChoice>
        @Suppress("UNCHECKED_CAST") val n = parts[4] as List<String>
        BillEditorState(m.loaded, m.failed, billId != null, f, preview(f, a, settingsValue, fxValue, day), c, a, n,
            settingsValue?.baseCurrency, fxValue.autoEnabled, m.phase, m.error, m.savedCount, m.attempted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillEditorState(form = form.value,
        preview = preview(form.value, emptyList(), null, FxContext(), emptyMap())))

    init {
        // A new bill starts on no account; choosing a category brings the account it was last used with.
        if (!meta.value.loaded) viewModelScope.launch { load() }
        // The stored rates of the bill's day, for its suggested rate.
        viewModelScope.launch {
            combine(form.map { it.occurredAt.toLocalDate() }.distinctUntilChanged(),
                settingsState.map { it?.baseCurrency }.distinctUntilChanged()) { day, base -> day to base }
                .collectLatest { (day, base) ->
                    dayRates.value = if (base == null) emptyMap() else runCatching { bills.storedRatesOn(base.code, day) }.getOrDefault(emptyMap())
                }
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)

    private fun newOperation(): String = UUID.randomUUID().toString().also { saved["operation"] = it }

    private suspend fun load() {
        try {
            val bill = bills.observeBill(billId ?: copyFromId ?: return).first() ?: throw DomainException(ErrorCode.NOT_FOUND)
            val copy = billId == null
            setForm(formOf(bill, copy))
            setMeta(meta.value.copy(loaded = true, revision = if (copy) null else bill.revision))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { setMeta(meta.value.copy(failed = true)) }
    }

    private fun formOf(bill: Bill, copy: Boolean): BillForm {
        val tab = when (bill.type) { BillType.INCOME -> BillTab.INCOME; BillType.TRANSFER -> BillTab.TRANSFER; else -> BillTab.EXPENSE }
        val time = if (copy) now() else Instant.ofEpochMilli(bill.occurredAtMs).atZone(clock.zone).toLocalDateTime()
        val fraction = bill.currency.fraction_digits
        val cross = bill.type != BillType.TRANSFER && bill.currency != bill.accountCurrency
        val transferCross = bill.type == BillType.TRANSFER && bill.transferKind == TransferKind.INTERNAL &&
            bill.toCurrency != null && bill.toCurrency != bill.accountCurrency
        return BillForm(
            tab = tab,
            amount = BillExpression.of(bill.amountMinor, fraction),
            discount = if (bill.discountMinor > 0) BillExpression.of(bill.discountMinor, fraction) else BillExpression(),
            currencyCode = if (bill.currency != bill.accountCurrency) bill.currency.code else null,
            cashAccountId = bill.cashAccountId,
            accountChosen = true,
            fromExternal = bill.transferKind == TransferKind.FROM_EXTERNAL,
            toCashAccountId = bill.toCashAccountId,
            toExternal = bill.transferKind == TransferKind.TO_EXTERNAL,
            counterparty = bill.counterparty,
            fee = if (bill.feeMinor > 0) BillExpression.of(bill.feeMinor, bill.accountCurrency.fraction_digits) else BillExpression(),
            toAmount = bill.toAmountMinor?.let { BillExpression.of(it, (bill.toCurrency ?: bill.accountCurrency).fraction_digits) } ?: BillExpression(),
            // An edit keeps the bill's own rate unless the user changes it.
            rate = if (cross || transferCross) bill.rate.stripTrailingZeros().toPlainString() else null,
            accountAmount = if (cross && bill.rateSource == BillRateSource.DERIVED)
                BillExpression.of(bill.accountAmountMinor, bill.accountCurrency.fraction_digits) else null,
            transferEdits = listOf(TransferFigure.RATE, TransferFigure.OUT, TransferFigure.IN),
            expenseCategoryId = if (tab == BillTab.EXPENSE) bill.categoryId else null,
            expenseSubId = if (tab == BillTab.EXPENSE) bill.subCategoryId else null,
            incomeCategoryId = if (tab == BillTab.INCOME) bill.categoryId else null,
            incomeSubId = if (tab == BillTab.INCOME) bill.subCategoryId else null,
            occurredAt = time,
            countInStats = bill.countInStats,
            note = bill.note,
        )
    }

    private fun setForm(value: BillForm) {
        form.value = value
        saved["form"] = encode(value)
    }

    private fun setMeta(value: Meta) {
        meta.value = value
        saved["loaded"] = value.loaded
        saved["revision"] = value.revision
        saved["phase"] = value.phase.name
    }

    private val editable: Boolean get() = meta.value.phase == BillSavePhase.IDLE

    fun update(transform: (BillForm) -> BillForm) {
        if (editable) setForm(transform(form.value))
        if (meta.value.error != null) setMeta(meta.value.copy(error = null))
    }

    fun selectTab(tab: BillTab) = update { it.copy(tab = tab, target = BillField.AMOUNT) }

    fun focus(field: BillField) = update { it.copy(target = field) }

    /** A key on the keypad, typed into the field in focus. */
    fun press(key: BillKey) = update { f ->
        val preview = state.value.preview
        when (f.target) {
            BillField.AMOUNT -> f.copy(amount = f.amount.press(key, (if (f.tab == BillTab.TRANSFER) preview.accountCurrency else preview.currency)?.fraction_digits ?: 2))
                .let { if (it.tab == BillTab.TRANSFER) touched(it, TransferFigure.OUT) else it }
            BillField.DISCOUNT -> f.copy(discount = f.discount.press(key, preview.currency?.fraction_digits ?: 2))
            BillField.FEE -> f.copy(fee = f.fee.press(key, preview.accountCurrency?.fraction_digits ?: 2))
            BillField.TO_AMOUNT -> touched(f.copy(toAmount = f.toAmount.press(key, preview.toCurrency?.fraction_digits ?: 2)), TransferFigure.IN)
            BillField.ACCOUNT_AMOUNT -> f.copy(accountAmount = (f.accountAmount ?: BillExpression()).press(key, preview.accountCurrency?.fraction_digits ?: 2))
            BillField.RATE -> {
                val typed = BillExpression(f.rate?.takeIf { !f.rateInverted } ?: f.rate?.let { invertedText(it) } ?: "")
                    .press(key, BillRules.RATE_SCALE)
                val value = typed.value(BillRules.RATE_SCALE)
                val rate = when {
                    typed.isEmpty -> null
                    value == null || value.signum() <= 0 -> f.rate
                    f.rateInverted -> BigDecimal.ONE.divide(value, BillRules.RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    else -> typed.text
                }
                touched(f.copy(rate = rate, accountAmount = null), TransferFigure.RATE)
            }
        }
    }

    private fun invertedText(rate: String): String = rate.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        ?.let { BigDecimal.ONE.divide(it, BillRules.RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() } ?: ""

    /** Marks [figure] as just changed and works out the transfer figure changed longest ago from the other two. */
    private fun touched(f: BillForm, figure: TransferFigure): BillForm {
        if (f.tab != BillTab.TRANSFER) return f
        val order = f.transferEdits - figure + figure
        val next = f.copy(transferEdits = order)
        val preview = preview(next, accounts.value, settingsState.value, fxState.value, dayRates.value)
        if (!preview.crossCurrency) return next
        val from = preview.accountCurrency ?: return next
        val to = preview.toCurrency ?: return next
        val out = next.amount.minor(from.fraction_digits)
        val received = next.toAmount.minor(to.fraction_digits)
        val rate = next.rate?.toBigDecimalOrNull() ?: preview.rate
        return when (order.first()) {
            TransferFigure.IN -> if (out != null && rate != null) next.copy(toAmount = BillExpression.of(BillRules.convert(out, from, to, rate), to.fraction_digits)) else next
            TransferFigure.OUT -> if (received != null && rate != null && rate.signum() > 0)
                next.copy(amount = BillExpression.of(BillRules.convert(received, to, from,
                    BigDecimal.ONE.divide(rate, 24, RoundingMode.HALF_UP)), from.fraction_digits)) else next
            TransferFigure.RATE -> if (out != null && received != null && out > 0 && received > 0)
                next.copy(rate = BillRules.derivedRate(out, from, received, to).toPlainString()) else next
        }
    }

    /** The confirm key: "=" works the expression out; otherwise it saves. */
    fun confirm(again: Boolean = false) {
        val f = form.value
        val field = when (f.target) {
            BillField.AMOUNT -> f.amount
            BillField.DISCOUNT -> f.discount
            BillField.FEE -> f.fee
            BillField.TO_AMOUNT -> f.toAmount
            BillField.ACCOUNT_AMOUNT -> f.accountAmount ?: BillExpression()
            BillField.RATE -> BillExpression()
        }
        if (field.hasOperator) {
            val digits = 12
            update { current -> when (current.target) {
                BillField.AMOUNT -> current.copy(amount = current.amount.settled(state.value.preview.currency?.fraction_digits ?: digits))
                BillField.DISCOUNT -> current.copy(discount = current.discount.settled(state.value.preview.currency?.fraction_digits ?: digits))
                BillField.FEE -> current.copy(fee = current.fee.settled(state.value.preview.accountCurrency?.fraction_digits ?: digits))
                BillField.TO_AMOUNT -> current.copy(toAmount = current.toAmount.settled(state.value.preview.toCurrency?.fraction_digits ?: digits))
                BillField.ACCOUNT_AMOUNT -> current.copy(accountAmount = current.accountAmount?.settled(state.value.preview.accountCurrency?.fraction_digits ?: digits))
                BillField.RATE -> current
            } }
            return
        }
        if (state.value.preview.problem != null) { setMeta(meta.value.copy(attempted = true)); return }
        save(again)
    }

    /** [cashAccountId] null keeps the bill on no account. */
    fun chooseAccount(cashAccountId: Long?) = update { f ->
        val following = f.currencyCode == null
        f.copy(cashAccountId = cashAccountId, fromExternal = false, accountChosen = true,
            // A rate typed for the old pair no longer applies.
            rate = if (following) null else f.rate, accountAmount = null)
    }

    fun chooseTransferSource(cashAccountId: Long?) = update { f ->
        f.copy(cashAccountId = cashAccountId, fromExternal = cashAccountId == null, accountChosen = true, rate = null,
            toExternal = if (cashAccountId == null) false else f.toExternal)
    }

    fun chooseTransferTarget(cashAccountId: Long?) = update { f ->
        f.copy(toCashAccountId = cashAccountId, toExternal = cashAccountId == null, rate = null,
            fromExternal = if (cashAccountId == null) false else f.fromExternal)
    }

    fun chooseCurrency(code: String) = update { f ->
        val accountCurrency = accounts.value.firstOrNull { it.cashAccountId == f.cashAccountId }?.currency?.code
            ?: settingsState.value?.baseCurrency?.code
        f.copy(currencyCode = code.takeIf { it != accountCurrency }, rate = null, accountAmount = null)
    }

    /**
     * A category tap. Until the user picks the account themselves, a new bill takes the account the category was last
     * used with: a top-level category's own (none when it has none), a subcategory's when it has one.
     */
    fun chooseCategory(categoryId: Long, subCategoryId: Long?) = update { f ->
        val next = if (f.tab == BillTab.INCOME) f.copy(incomeCategoryId = categoryId, incomeSubId = subCategoryId)
            else f.copy(expenseCategoryId = categoryId, expenseSubId = subCategoryId)
        if (billId != null || f.accountChosen) next else {
            val all = categories.value
            fun remembered(id: Long) = all.firstOrNull { it.id == id }?.lastCashAccountId
                ?.takeIf { account -> accounts.value.any { it.cashAccountId == account } }
            val account = if (subCategoryId == null) remembered(categoryId) else remembered(subCategoryId) ?: next.cashAccountId
            if (account == next.cashAccountId) next else next.copy(cashAccountId = account,
                rate = if (next.currencyCode == null) null else next.rate, accountAmount = null)
        }
    }

    fun setTime(value: LocalDateTime) = update { it.copy(occurredAt = value.withSecond(0).withNano(0)) }

    /** Today, yesterday or the day before, at the time already set. */
    fun setDaysAgo(days: Long) = update {
        it.copy(occurredAt = LocalDateTime.of(LocalDate.now(clock).minusDays(days), it.occurredAt.toLocalTime()))
    }

    fun setNote(value: String) = update { it.copy(note = value.take(BillRules.MAX_NOTE_LENGTH)) }

    fun setCounterparty(value: String) = update { it.copy(counterparty = value) }

    fun setCountInStats(value: Boolean) = update { it.copy(countInStats = value) }

    fun setDiscountMode(mode: DiscountMode) = update { f ->
        if (f.discountMode == mode) f else {
            // The same number stays meaningful: a discount of 15.70 on 68 becomes paid 52.30, and back.
            val digits = state.value.preview.currency?.fraction_digits ?: 2
            val amount = f.amount.minor(digits)
            val entered = f.discount.minor(digits)
            val swapped = if (amount != null && entered != null && entered in 0..amount) BillExpression.of(amount - entered, digits) else f.discount
            f.copy(discountMode = mode, discount = swapped, target = BillField.DISCOUNT)
        }
    }

    /** The picker's pinned sub-accounts, in order. */
    fun saveFavorites(orderedIds: List<Long>) {
        viewModelScope.launch {
            runCatching { commands.execute(SaveBillFavorites(UUID.randomUUID().toString(), orderedIds)) }
        }
    }

    fun flipRateDirection() = update { it.copy(rateInverted = !it.rateInverted) }

    /** Back to the suggested rate and the amount it gives. */
    fun resetRate() = update { touched(it.copy(rate = null, accountAmount = null), TransferFigure.RATE) }

    // Saving

    fun save(again: Boolean = false) {
        if (!editable && meta.value.phase != BillSavePhase.UNKNOWN) return
        val current = state.value
        val command = try {
            SaveBill(operationId, billId, if (billId == null) null else meta.value.revision ?: throw DomainException(ErrorCode.NOT_FOUND),
                draft(current.form, current.preview))
        } catch (error: DomainException) { setMeta(meta.value.copy(error = error.code)); return }
        val reconcile = meta.value.phase == BillSavePhase.UNKNOWN
        setMeta(meta.value.copy(phase = BillSavePhase.WORKING, error = null))
        viewModelScope.launch {
            try {
                if (reconcile) commands.operationResult(command.operation_id) ?: commands.execute(command)
                else commands.execute(command)
                if (again && billId == null) {
                    operationId = newOperation()
                    setForm(current.form.copy(amount = BillExpression(), discount = BillExpression(), fee = BillExpression(),
                        toAmount = BillExpression(), accountAmount = null, note = "", target = BillField.AMOUNT))
                    setMeta(meta.value.copy(phase = BillSavePhase.IDLE, savedCount = meta.value.savedCount + 1))
                } else setMeta(meta.value.copy(phase = BillSavePhase.SAVED))
                setMeta(meta.value.copy(attempted = false))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { setMeta(meta.value.copy(phase = BillSavePhase.IDLE, error = error.code)) }
            catch (_: Exception) { setMeta(meta.value.copy(phase = BillSavePhase.UNKNOWN)) }
        }
    }

    fun consumeSaved(): Boolean = meta.value.phase == BillSavePhase.SAVED

    private fun draft(f: BillForm, p: BillPreview): BillDraft {
        p.problem?.let { throw DomainException(when (it) {
            BillProblem.AMOUNT, BillProblem.DIVIDE -> ErrorCode.POSITIVE
            BillProblem.DISCOUNT -> ErrorCode.FORMAT
            BillProblem.ACCOUNT, BillProblem.SAME_ACCOUNT, BillProblem.BOTH_EXTERNAL -> ErrorCode.WRONG_CASH_ACCOUNT
            BillProblem.CATEGORY -> ErrorCode.FORMAT
            BillProblem.RATE -> ErrorCode.CURRENCY
        }) }
        val time = f.occurredAt.atZone(clock.zone).toInstant().toEpochMilli()
        val amount = p.amountMinor!!
        if (f.tab == BillTab.TRANSFER) {
            val kind = when { f.fromExternal -> TransferKind.FROM_EXTERNAL; f.toExternal -> TransferKind.TO_EXTERNAL; else -> TransferKind.INTERNAL }
            val fee = if (kind == TransferKind.FROM_EXTERNAL) 0 else f.fee.minor(p.accountCurrency!!.fraction_digits) ?: 0
            return BillDraft(BillType.TRANSFER, time, amount, p.accountCurrency!!.code, 0,
                if (kind == TransferKind.FROM_EXTERNAL) null else f.cashAccountId, amount, p.rate ?: BigDecimal.ONE,
                p.rateSource ?: BillRateSource.SAME, kind, if (kind == TransferKind.TO_EXTERNAL) null else f.toCashAccountId,
                when (kind) { TransferKind.TO_EXTERNAL -> null; TransferKind.FROM_EXTERNAL -> amount; else -> p.convertedMinor },
                fee, if (kind == TransferKind.INTERNAL) "" else f.counterparty, countInStats = false, note = f.note)
        }
        return BillDraft(if (f.tab == BillTab.INCOME) BillType.INCOME else BillType.EXPENSE, time, amount, p.currency!!.code,
            p.discountMinor, f.cashAccountId, p.convertedMinor!!, p.rate ?: BigDecimal.ONE, p.rateSource ?: BillRateSource.SAME,
            categoryId = f.categoryId, subCategoryId = f.subCategoryId, countInStats = f.countInStats, note = f.note)
    }

    companion object {
        /** Works out every figure the form shows and whether it can be saved. */
        fun preview(f: BillForm, accounts: List<BillAccountChoice>, settings: AppSettings?, fx: FxContext,
            dayRates: Map<String, BigDecimal>): BillPreview {
            val own = accounts.firstOrNull { it.cashAccountId == f.cashAccountId }
            if (f.tab == BillTab.TRANSFER) return transferPreview(f, accounts, settings, fx, dayRates)
            // On no account the bill keeps its own currency (the base one unless chosen) and is not converted.
            val currency = f.currencyCode?.let(Currency::of) ?: own?.currency ?: settings?.baseCurrency
            val accountCurrency = own?.currency ?: currency
            val digits = currency?.fraction_digits ?: 2
            val amount = f.amount.minor(digits)
            val entered = f.discount.minor(digits)
            val discount = when {
                entered == null -> 0L
                f.discountMode == DiscountMode.DISCOUNT -> entered
                amount != null -> amount - entered
                else -> 0L
            }
            val paid = amount?.let { it - discount }
            val cross = currency != null && accountCurrency != null && currency != accountCurrency
            var rate: BigDecimal? = null
            var source: BillRateSource? = null
            var converted: Long? = null
            if (currency != null && accountCurrency != null && paid != null && paid > 0) {
                if (!cross) { rate = BigDecimal.ONE; source = BillRateSource.SAME; converted = paid }
                else {
                    val typedAmount = f.accountAmount?.minor(accountCurrency.fraction_digits)
                    val typedRate = f.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                    when {
                        typedAmount != null && typedAmount > 0 -> {
                            converted = typedAmount; source = BillRateSource.DERIVED
                            rate = BillRules.derivedRate(paid, currency, typedAmount, accountCurrency)
                        }
                        typedRate != null -> {
                            rate = typedRate.setScale(BillRules.RATE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros()
                            source = BillRateSource.ONE_TIME
                            converted = runCatching { BillRules.convert(paid, currency, accountCurrency, rate) }.getOrNull()
                        }
                        settings != null -> BillRates.suggest(currency, accountCurrency, settings, fx, dayRates)?.let {
                            rate = it.rate; source = it.source
                            converted = runCatching { BillRules.convert(paid, currency, accountCurrency, it.rate) }.getOrNull()
                        }
                    }
                }
            }
            val missingRate = cross && rate == null
            val problem = when {
                f.amount.text.isNotEmpty() && f.amount.value(digits) == null -> BillProblem.DIVIDE
                amount == null || amount <= 0 -> BillProblem.AMOUNT
                discount < 0 || discount >= amount -> BillProblem.DISCOUNT
                f.cashAccountId != null && own == null -> BillProblem.ACCOUNT
                f.categoryId == null -> BillProblem.CATEGORY
                missingRate || converted == null || converted <= 0 -> BillProblem.RATE
                else -> null
            }
            return BillPreview(currency, accountCurrency, null, amount, discount, paid, converted, rate, source, cross,
                missingRate, problem)
        }

        private fun transferPreview(f: BillForm, accounts: List<BillAccountChoice>, settings: AppSettings?, fx: FxContext,
            dayRates: Map<String, BigDecimal>): BillPreview {
            val from = if (f.fromExternal) null else accounts.firstOrNull { it.cashAccountId == f.cashAccountId }
            val to = if (f.toExternal) null else accounts.firstOrNull { it.cashAccountId == f.toCashAccountId }
            // The amount is in the source's currency, or the receiving account's when money comes from outside.
            val currency = from?.currency ?: to?.currency
            val toCurrency = if (f.fromExternal) currency else to?.currency
            val amount = currency?.let { f.amount.minor(it.fraction_digits) }
            val cross = from != null && to != null && from.currency != to.currency
            var rate: BigDecimal? = BigDecimal.ONE
            var source: BillRateSource? = BillRateSource.SAME
            var received: Long? = amount
            if (cross) {
                val typedRate = f.rate?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                val suggestion = settings?.let { BillRates.suggest(from!!.currency, to!!.currency, it, fx, dayRates) }
                rate = typedRate?.setScale(BillRules.RATE_SCALE, RoundingMode.HALF_UP)?.stripTrailingZeros() ?: suggestion?.rate
                source = if (typedRate != null) BillRateSource.ONE_TIME else suggestion?.source
                received = f.toAmount.minor(to!!.currency.fraction_digits)
                // Whatever was typed, the stored rate has to give the received amount back exactly: if rounding means
                // it does not, the rate is worked out from the two amounts instead.
                if (amount != null && amount > 0 && received != null && received > 0 && rate != null &&
                    runCatching { BillRules.convert(amount, from.currency, to.currency, rate) }.getOrNull() != received) {
                    rate = BillRules.derivedRate(amount, from.currency, received, to.currency); source = BillRateSource.DERIVED
                }
            }
            val missingRate = cross && rate == null
            val problem = when {
                f.amount.text.isNotEmpty() && currency != null && f.amount.value(currency.fraction_digits) == null -> BillProblem.DIVIDE
                f.fromExternal && f.toExternal -> BillProblem.BOTH_EXTERNAL
                (!f.fromExternal && from == null) || (!f.toExternal && to == null) -> BillProblem.ACCOUNT
                from != null && to != null && from.cashAccountId == to.cashAccountId -> BillProblem.SAME_ACCOUNT
                amount == null || amount <= 0 -> BillProblem.AMOUNT
                missingRate || received == null || received <= 0 -> BillProblem.RATE
                else -> null
            }
            return BillPreview(currency, currency, toCurrency, amount, 0, amount, received, rate, source, cross, missingRate, problem)
        }

        internal fun encode(f: BillForm): String = JSONObject().apply {
            put("tab", f.tab.name); put("amount", f.amount.text); put("discount", f.discount.text)
            put("discountMode", f.discountMode.name); putOpt("currency", f.currencyCode); putOpt("cash", f.cashAccountId)
            put("chosen", f.accountChosen); put("fromExternal", f.fromExternal); putOpt("to", f.toCashAccountId)
            put("toExternal", f.toExternal); put("counterparty", f.counterparty); put("fee", f.fee.text)
            put("toAmount", f.toAmount.text); putOpt("rate", f.rate); put("rateInverted", f.rateInverted)
            putOpt("accountAmount", f.accountAmount?.text); put("edits", JSONArray(f.transferEdits.map { it.name }))
            putOpt("expense", f.expenseCategoryId); putOpt("expenseSub", f.expenseSubId)
            putOpt("income", f.incomeCategoryId); putOpt("incomeSub", f.incomeSubId); put("time", f.occurredAt.toString())
            put("count", f.countInStats); put("note", f.note); put("target", f.target.name)
        }.toString()

        internal fun decode(text: String): BillForm? = runCatching {
            val o = JSONObject(text)
            fun long(key: String) = if (o.isNull(key) || !o.has(key)) null else o.getLong(key)
            fun string(key: String) = if (o.isNull(key) || !o.has(key)) null else o.getString(key)
            BillForm(BillTab.valueOf(o.getString("tab")), BillExpression(o.getString("amount")), BillExpression(o.getString("discount")),
                DiscountMode.valueOf(o.getString("discountMode")), string("currency"), long("cash"), o.getBoolean("chosen"),
                o.getBoolean("fromExternal"), long("to"), o.getBoolean("toExternal"), o.getString("counterparty"),
                BillExpression(o.getString("fee")), BillExpression(o.getString("toAmount")), string("rate"),
                o.getBoolean("rateInverted"), string("accountAmount")?.let(::BillExpression),
                o.getJSONArray("edits").let { a -> (0 until a.length()).map { TransferFigure.valueOf(a.getString(it)) } },
                long("expense"), long("expenseSub"), long("income"), long("incomeSub"),
                LocalDateTime.parse(o.getString("time")), o.getBoolean("count"), o.getString("note"), BillField.valueOf(o.getString("target")))
        }.getOrNull()
    }
}
