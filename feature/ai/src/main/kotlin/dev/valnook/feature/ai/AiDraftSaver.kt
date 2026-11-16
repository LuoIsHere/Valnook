package dev.valnook.feature.ai

import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.bills.BillForm
import dev.valnook.feature.bills.BillPrefill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.util.UUID

/**
 * A draft across currencies: [fromMinor] of [from] (what is paid, or what leaves in a transfer) and, at the rate
 * the bill would get, [toMinor] of [to] (what the account moves, or what arrives); [missingRate] when there is none.
 * [given] when that rate came from the user (a rate, or the amount charged or received): [rate] is it, and [farOff]
 * when it is more than [AiDraftSaver.FAR_OFF] away from the app's own rate, perhaps said the other way round.
 */
data class AiDraftMoney(val from: String, val fromDigits: Int, val fromMinor: Long?, val to: String, val toDigits: Int,
    val toMinor: Long?, val missingRate: Boolean, val given: Boolean = false, val rate: BigDecimal? = null,
    val source: BillRateSource? = null, val farOff: Boolean = false)

/** A saved bill as a card shows it, and the pictures it has. */
data class AiSavedBill(val draft: AiBillDraft, val images: List<BillImage>)

/** What confirming a draft came to. */
sealed interface AiSaveOutcome {
    data class Saved(val billId: Long) : AiSaveOutcome
    /** The bill page would not save it as it is (no amount, no rate…): it opens there, filled in. */
    data class Open(val prefill: String) : AiSaveOutcome
    data class Failed(val code: ErrorCode?) : AiSaveOutcome
}

/** Saves AI drafts exactly as the bill page saves a form, and takes a saved one back. */
class AiDraftSaver(
    private val bills: BillRepository,
    private val commands: FinancialCommands,
    private val settings: Flow<AppSettings>,
    private val fx: Flow<FxContext>,
    private val clock: Clock,
    private val images: BillImageLibrary?,
) {
    /** The bill page's form for [draft], with [pictures] that are still on this device. */
    suspend fun form(draft: AiBillDraft, pictures: List<BillImage>): BillForm {
        val library = images
        val kept = if (library == null) emptyList()
            else pictures.filter { runCatching { library.place(it.key) }.getOrNull() == BillImagePlace.DEVICE }
        return BillPrefill.form(draft.type, draft.amountMinor, draft.currencyCode, draft.occurredAt,
            bills.observeAccountChoices().first(), settings.first().baseCurrency?.code, draft.discountMinor,
            draft.cashAccountId, draft.toCashAccountId, draft.categoryId, draft.subCategoryId, draft.note,
            draft.counterparty, draft.reimbursable, kept, draft.toAmountMinor, draft.rate, draft.accountAmountMinor)
    }

    /**
     * What saving [draft] would move, worked out as saving does: across currencies the account's side at the rate
     * the bill would get (that day's stored rate, else the current or manual one), or that no rate is there.
     */
    suspend fun money(draft: AiBillDraft): AiDraftMoney? = runCatching {
        val form = form(draft, emptyList())
        val appSettings = settings.first()
        val dayRates = appSettings.baseCurrency?.let { base ->
            runCatching { bills.storedRatesOn(base.code, form.occurredAt.toLocalDate()) }.getOrDefault(emptyMap())
        }.orEmpty()
        val (_, preview) = BillPrefill.complete(form, bills.observeAccountChoices().first(), appSettings, fx.first(), dayRates)
        if (!preview.crossCurrency) return@runCatching null
        val from = (if (draft.type == BillType.TRANSFER) preview.accountCurrency else preview.currency) ?: return@runCatching null
        val to = (if (draft.type == BillType.TRANSFER) preview.toCurrency else preview.accountCurrency) ?: return@runCatching null
        val given = preview.rateSource == BillRateSource.ONE_TIME || preview.rateSource == BillRateSource.DERIVED
        val reference = if (given) BillRates.suggest(from, to, appSettings, fx.first(), dayRates)?.rate else null
        val rate = preview.rate
        val farOff = reference != null && reference.signum() > 0 && rate != null &&
            (rate.divide(reference, MathContext.DECIMAL64) - BigDecimal.ONE).abs() > FAR_OFF
        AiDraftMoney(from.code, from.fraction_digits, preview.amountMinor?.let { it - preview.discountMinor },
            to.code, to.fraction_digits, preview.convertedMinor?.takeIf { !preview.missingRate }, preview.missingRate,
            given, rate, preview.rateSource, farOff)
    }.getOrNull()

    suspend fun prefill(draft: AiBillDraft, pictures: List<BillImage>): String = BillPrefill.encode(form(draft, pictures))

    /** Saves [draft] under [operationId]; a save that already went through under it is found instead of repeated. */
    suspend fun save(draft: AiBillDraft, pictures: List<BillImage>, operationId: String): AiSaveOutcome = try {
        savedAs(operationId)?.let { AiSaveOutcome.Saved(it) } ?: run {
            val form = form(draft, pictures)
            val appSettings = settings.first()
            val day = form.occurredAt.toLocalDate()
            val dayRates = appSettings.baseCurrency?.let { base ->
                runCatching { bills.storedRatesOn(base.code, day) }.getOrDefault(emptyMap())
            }.orEmpty()
            val bill = BillPrefill.draft(form, bills.observeAccountChoices().first(), appSettings, fx.first(), dayRates, clock.zone)
            if (bill == null) AiSaveOutcome.Open(BillPrefill.encode(form))
            else AiSaveOutcome.Saved(commands.execute(SaveBill(operationId, null, null, bill)).id)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: DomainException) { AiSaveOutcome.Failed(error.code) }
    catch (_: Exception) { AiSaveOutcome.Failed(null) }

    companion object {
        /** How far a rate the user gave may be from the app's own before the card asks to check it. */
        val FAR_OFF: BigDecimal = BigDecimal("0.3")
    }

    /**
     * Bill [billId] as it is now, as a card shows it: what was charged or received across currencies is given, so the
     * card shows the bill's own figures, and the checks' flags are gone (the user saw it). Null when the bill is gone.
     */
    suspend fun billNow(billId: Long): AiSavedBill? {
        val bill = bills.observeBill(billId).first() ?: return null
        val cross = bill.type != BillType.TRANSFER && bill.currency != bill.accountCurrency
        val transferCross = bill.type == BillType.TRANSFER && bill.toCurrency != null && bill.toCurrency != bill.currency
        val draft = AiBillDraft(bill.type, bill.amountMinor, bill.currency.code, bill.discountMinor, bill.categoryId,
            bill.subCategoryId, bill.cashAccountId, bill.toCashAccountId,
            java.time.Instant.ofEpochMilli(bill.occurredAtMs).atZone(clock.zone).toLocalDateTime(), bill.note,
            bill.counterparty, bill.reimbursable, toAmountMinor = bill.toAmountMinor?.takeIf { transferCross },
            accountAmountMinor = bill.accountAmountMinor.takeIf { cross })
        return AiSavedBill(draft, bills.observeImages(billId).first())
    }

    /** The bill saved under [operationId], if one was. */
    suspend fun savedAs(operationId: String): Long? = runCatching { commands.operationResult(operationId)?.id }.getOrNull()

    /** Deletes bill [billId] again, moving its accounts back; true when it is gone. */
    suspend fun delete(billId: Long): Boolean = try {
        val bill = bills.observeBill(billId).first()
        if (bill != null) commands.execute(DeleteBill(UUID.randomUUID().toString(), billId, bill.revision))
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}
