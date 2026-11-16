package dev.valnook.feature.ai

import dev.valnook.designsystem.SecondaryButton
import dev.valnook.designsystem.PrimaryButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.Space
import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.ai.AiDraftCard
import dev.valnook.domain.ai.AiDraftFlag
import dev.valnook.domain.ai.AiDraftState
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillRateSource
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.Currency
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.feature.bills.BillImageThumb
import dev.valnook.feature.bills.BillImageViewer
import dev.valnook.feature.bills.CategoryIcon
import dev.valnook.feature.bills.categoryName
import dev.valnook.feature.bills.label
import dev.valnook.feature.bills.plainAmount
import dev.valnook.feature.bills.shortRate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What a draft card can do; the share page and the chat page each wire their own. */
class AiDraftActions(
    val onConfirm: (AiDraftCard) -> Unit,
    val onEdit: (AiDraftCard) -> Unit,
    val onDiscard: (AiDraftCard) -> Unit,
    val onUndo: (AiDraftCard) -> Unit,
    /** Null where a saved bill cannot be opened (the share page). */
    val onOpenBill: ((Long) -> Unit)?,
    val onKeepImages: (AiDraftCard, Boolean) -> Unit,
    /** Opens the exchange rate settings, for a draft across currencies without a rate. */
    val onRates: (() -> Unit)? = null,
    /** Opens a saved card's bill in the bill page, to change it again; null where that is not offered. */
    val onEditBill: ((AiDraftCard) -> Unit)? = null,
)

/**
 * One draft: type, amount and currency, category, account, time and note, with what the checks filled in. A waiting
 * draft can be confirmed (or, without an amount, completed in the bill page), edited or discarded; a saved one is
 * changed again in the bill page (unless [billGone]), opens its bill or is taken back.
 */
@Composable
fun AiDraftCardView(card: AiDraftCard, categories: Map<Long, BillCategory>, accounts: Map<Long, BillAccountChoice>,
    busy: Boolean, actions: AiDraftActions, modifier: Modifier = Modifier, money: AiDraftMoney? = null,
    billGone: Boolean = false) {
    val draft = card.draft
    val colors = MaterialTheme.colorScheme
    val faded = card.state == AiDraftState.DISCARDED || card.state == AiDraftState.REPLACED
    Surface(modifier.fillMaxWidth().testTag("ai-draft-${card.label}-${card.state.name.lowercase()}"),
        shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
        Column(Modifier.padding(Space.md).then(if (faded) Modifier.alpha(0.5f) else Modifier),
            verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            DraftHeadline(draft, categories, accounts, money)
            if (card.state == AiDraftState.PENDING) Conversion(card, money, actions)
            DraftDetails(draft, accounts)
            if (draft.note.isNotBlank()) Text(draft.note, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            DraftFlags(draft)
            // A discarded or replaced card let its pictures go; the message it came from still shows them.
            if (card.images.isNotEmpty() && (card.state == AiDraftState.PENDING || card.state == AiDraftState.CONFIRMED))
                DraftPictures(card, actions)
            when (card.state) {
                AiDraftState.PENDING -> PendingButtons(card, busy, actions, money?.missingRate == true)
                AiDraftState.CONFIRMED -> {
                    Text(stringResource(R.string.ai_draft_saved), color = colors.primary, style = MaterialTheme.typography.labelLarge)
                    // On a line of their own, so three buttons never squeeze the words on a narrow screen.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        val edit = actions.onEditBill
                        if (edit != null && card.billId != null && !billGone) SecondaryButton(stringResource(R.string.ai_draft_edit),
                            { edit(card) }, Modifier.testTag("ai-draft-${card.label}-edit-bill"), enabled = !busy)
                        val open = actions.onOpenBill
                        if (open != null) card.billId?.let { id -> SecondaryButton(stringResource(R.string.ai_chat_view_bill), { open(id) },
                            Modifier.testTag("ai-draft-${card.label}-view")) }
                        SecondaryButton(stringResource(R.string.ai_draft_undo), { actions.onUndo(card) },
                            Modifier.testTag("ai-draft-${card.label}-undo"), enabled = !busy)
                    }
                }
                AiDraftState.DISCARDED -> Text(stringResource(R.string.ai_draft_discarded), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
                AiDraftState.REPLACED -> Text(stringResource(R.string.ai_draft_replaced), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DraftHeadline(draft: AiBillDraft, categories: Map<Long, BillCategory>, accounts: Map<Long, BillAccountChoice>,
    money: AiDraftMoney?) {
    val category = (draft.subCategoryId ?: draft.categoryId)?.let(categories::get)
    val parent = draft.categoryId?.let(categories::get)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (draft.type != BillType.TRANSFER && category != null) CategoryIcon(category.iconKey, category.colorKey, size = 32.dp)
        val title = when {
            draft.type == BillType.TRANSFER -> stringResource(R.string.ai_draft_transfer)
            parent == null -> stringResource(R.string.ai_draft_no_category)
            draft.subCategoryId != null && category != null && category.id != parent.id -> categoryName(parent) + " · " + categoryName(category)
            else -> categoryName(parent)
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(amountText(draft, money), style = MaterialTheme.typography.titleMedium, color = when (draft.type) {
            BillType.INCOME -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        })
    }
}

/** "−68.00 CNY", "+100.00 USD", or a dash while the amount is missing; a transfer said by what arrived shows what left. */
@Composable
private fun amountText(draft: AiBillDraft, money: AiDraftMoney?): String {
    if (draft.amountMinor == null && draft.toAmountMinor != null)
        return money?.fromMinor?.let { "≈ " + plainAmount(it, money.fromDigits) + " " + money.from } ?: "—"
    val minor = draft.amountMinor ?: return "—"
    val digits = runCatching { Currency.of(draft.currencyCode).fraction_digits }.getOrDefault(2)
    val sign = when (draft.type) { BillType.EXPENSE -> "−"; BillType.INCOME -> "+"; else -> "" }
    return "$sign${plainAmount(minor - draft.discountMinor, digits)} ${draft.currencyCode}"
}

/**
 * Across currencies: what the account moves at the rate the bill would get, what arrives in a transfer, or that no
 * rate is there yet, with the way to the rate settings.
 */
@Composable
private fun Conversion(card: AiDraftCard, money: AiDraftMoney?, actions: AiDraftActions) {
    val draft = card.draft
    if (money == null) return
    if (money.missingRate) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ai-draft-${card.label}-no-rate")) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.ai_draft_no_rate, money.from, money.to),
                    Modifier.padding(horizontal = Space.sm, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            actions.onRates?.let { open ->
                SecondaryButton(stringResource(R.string.ai_draft_rate_settings), open,
                    Modifier.padding(start = Space.xs).testTag("ai-draft-${card.label}-rates"))
            }
        }
        return
    }
    val to = money.toMinor?.let { plainAmount(it, money.toDigits) + " " + money.to } ?: return
    val transfer = draft.type == BillType.TRANSFER
    // An amount that was said is shown as it is; anything worked out at a rate is marked as about, and a rate the
    // user gave says so.
    val text = when {
        transfer && draft.toAmountMinor != null -> stringResource(R.string.ai_draft_received, to)
        draft.accountAmountMinor != null -> stringResource(R.string.ai_draft_charged_given, to)
        money.given && money.source == BillRateSource.ONE_TIME && money.rate != null -> stringResource(
            if (transfer) R.string.ai_draft_received_rate else R.string.ai_draft_charged_rate, "≈ $to", shortRate(money.rate))
        else -> stringResource(if (transfer) R.string.ai_draft_received else R.string.ai_draft_charged, "≈ $to")
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ai-draft-${card.label}-converted"))
    if (money.given && money.farOff) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.testTag("ai-draft-${card.label}-rate-far")) {
        Text(stringResource(R.string.ai_draft_rate_far), Modifier.padding(horizontal = Space.sm, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun DraftDetails(draft: AiBillDraft, accounts: Map<Long, BillAccountChoice>) {
    val locale = LocalConfiguration.current.locales[0]
    val none = stringResource(R.string.ai_draft_no_account)
    val outside = stringResource(R.string.ai_draft_outside)
    val account = if (draft.type == BillType.TRANSFER) {
        val from = draft.cashAccountId?.let(accounts::get)?.label() ?: if (draft.toCashAccountId != null) outside else none
        val to = draft.toCashAccountId?.let(accounts::get)?.label() ?: if (draft.cashAccountId != null) outside else none
        "$from → $to"
    } else draft.cashAccountId?.let(accounts::get)?.label() ?: none
    val time = draft.occurredAt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
    // The currency paid, by name and code, first: a share's currency is read off it and worth checking.
    val currency = runCatching { Currency.of(draft.currencyCode) }.getOrNull()
        ?.let { "${it.display_name(locale.language)} ${it.code}" } ?: draft.currencyCode
    val extras = buildList {
        if (draft.type != BillType.TRANSFER) add(currency)
        add(account)
        add(time)
        if (draft.discountMinor > 0) add(stringResource(R.string.ai_draft_discount,
            plainAmount(draft.discountMinor, runCatching { Currency.of(draft.currencyCode).fraction_digits }.getOrDefault(2))))
        if (draft.reimbursable) add(stringResource(R.string.ai_draft_reimbursable) +
            draft.counterparty.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
        else if (draft.type == BillType.TRANSFER && draft.counterparty.isNotBlank()) add(draft.counterparty)
    }
    Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ai-draft-details"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftFlags(draft: AiBillDraft) {
    if (draft.flags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        listOf(AiDraftFlag.AMOUNT, AiDraftFlag.CURRENCY, AiDraftFlag.CATEGORY, AiDraftFlag.ACCOUNT, AiDraftFlag.TIME)
            .filter { it in draft.flags }.forEach { flag ->
            Surface(shape = RoundedCornerShape(8.dp), color = if (flag == AiDraftFlag.AMOUNT) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.testTag("ai-draft-flag-${flag.name.lowercase()}")) {
                Text(stringResource(when (flag) {
                    AiDraftFlag.AMOUNT -> R.string.ai_draft_flag_amount
                    AiDraftFlag.CATEGORY -> R.string.ai_draft_flag_category
                    AiDraftFlag.ACCOUNT -> R.string.ai_draft_flag_account
                    AiDraftFlag.TIME -> R.string.ai_draft_flag_time
                    AiDraftFlag.CURRENCY -> R.string.ai_draft_flag_currency
                }), Modifier.padding(horizontal = Space.sm, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (flag == AiDraftFlag.AMOUNT) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun DraftPictures(card: AiDraftCard, actions: AiDraftActions) {
    var viewing by remember { mutableStateOf<Int?>(null) }
    val keys = card.images.map(BillImage::key)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        itemsIndexed(keys) { index, key -> BillImageThumb(key, 56.dp, { viewing = index }) }
    }
    if (card.state == AiDraftState.PENDING) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(card.keepImages, { actions.onKeepImages(card, it) }, Modifier.testTag("ai-draft-${card.label}-keep-images"))
        Text(stringResource(R.string.ai_draft_keep_images), style = MaterialTheme.typography.bodyMedium)
    }
    viewing?.let { BillImageViewer(keys, it) { viewing = null } }
}

@Composable
private fun PendingButtons(card: AiDraftCard, busy: Boolean, actions: AiDraftActions, missingRate: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.End),
        verticalAlignment = Alignment.CenterVertically) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        SecondaryButton(stringResource(R.string.ai_draft_discard), { actions.onDiscard(card) },
            Modifier.testTag("ai-draft-${card.label}-discard"), enabled = !busy)
        SecondaryButton(stringResource(R.string.ai_draft_edit), { actions.onEdit(card) },
            Modifier.testTag("ai-draft-${card.label}-edit"), enabled = !busy)
        val missing = !card.draft.hasAmount
        // Without an amount or a rate the bill page finishes it; a rate typed there is for this bill only.
        PrimaryButton(stringResource(when {
            missing -> R.string.ai_draft_add_amount
            missingRate -> R.string.ai_draft_add_rate
            else -> R.string.ai_draft_confirm
        }), { if (missing || missingRate) actions.onEdit(card) else actions.onConfirm(card) },
            Modifier.testTag("ai-draft-${card.label}-confirm"), enabled = !busy)
    }
}
