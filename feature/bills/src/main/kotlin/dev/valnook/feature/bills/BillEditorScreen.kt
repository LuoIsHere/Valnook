package dev.valnook.feature.bills

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.Currency
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.BillAccountChoice
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The bill form. The figures and categories scroll; under them, always in the same place, a row of pills (account,
 * time, whether it counts), a low note box with notes used before, and the keypad. The keypad gives way to the
 * system keyboard while the note is typed; the pills and the note stay above it.
 */
@Composable fun BillEditorScreen(vm: BillEditorViewModel, onDone: () -> Unit, onManageCategories: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.phase) { if (vm.consumeSaved()) onDone() }
    if (state.failed) { EmptyState(stringResource(R.string.bill_unavailable)); return }
    if (!state.loaded) { PageLoading(); return }
    val f = state.form
    val p = state.preview
    var picking by remember { mutableStateOf<AccountPickTarget?>(null) }
    var timeOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val accounts = remember(state.accounts) { state.accounts.associateBy { it.cashAccountId } }
    val pictures = rememberPicturePicker(state, vm)
    // No bottom padding here: the keypad paints under the navigation bar and keeps its room inside.
    Column(Modifier.fillMaxSize().testTag("bill-editor")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Spacer(Modifier.height(LocalPageTopSpace.current))
            if (!state.editing || f.tab != BillTab.TRANSFER) CapsuleChoiceRow(
                // An edit stays the kind of bill it is between expense and income; a transfer stays a transfer.
                if (state.editing) BillTab.entries.filter { (it == BillTab.TRANSFER) == (f.tab == BillTab.TRANSFER) } else BillTab.entries,
                f.tab, vm::selectTab, optionModifier = { Modifier.testTag("bill-tab-${it.name}") }) { Text(stringResource(it.label)) }
            if (f.tab == BillTab.TRANSFER) TransferFields(state, accounts, vm, onPick = { picking = it })
            else SpendingFields(state, vm, onManageCategories)
            if (pictures.available && (f.images.isNotEmpty() || state.imagesWorking > 0 || state.imageFailed))
                EditorImages(state, vm, onAdd = { pictures.menu = true })
            Spacer(Modifier.height(Space.sm))
        }
        BottomBlock(state, accounts, vm, onAccount = { picking = AccountPickTarget.OWN }, onTime = { timeOpen = true }, pictures = pictures)
        if (!keyboardOpen) BillKeypad(
            confirmLabel = if (currentField(f).hasOperator) "=" else stringResource(if (state.editing) R.string.bill_save else R.string.bill_confirm),
            confirmEnabled = state.phase != BillSavePhase.WORKING,
            againEnabled = !state.editing && state.phase == BillSavePhase.IDLE,
            onKey = vm::press, onConfirm = { vm.confirm() }, onAgain = { vm.confirm(again = true) },
            bottomSpace = LocalPageBottomSpace.current)
    }
    picking?.let { target ->
        BillAccountPicker(state.accounts,
            selected = when (target) { AccountPickTarget.TO -> f.toCashAccountId; else -> f.cashAccountId },
            option = if (target == AccountPickTarget.OWN) PickerOption.NO_ACCOUNT else PickerOption.EXTERNAL,
            optionSelected = when (target) {
                AccountPickTarget.OWN -> f.cashAccountId == null
                AccountPickTarget.FROM -> f.fromExternal
                AccountPickTarget.TO -> f.toExternal
            },
            onPick = { id ->
                when (target) {
                    AccountPickTarget.OWN -> vm.chooseAccount(id)
                    AccountPickTarget.FROM -> vm.chooseTransferSource(id)
                    AccountPickTarget.TO -> vm.chooseTransferTarget(id)
                }
                picking = null
            }, onFavorites = vm::saveFavorites, onDismiss = { picking = null })
    }
    if (timeOpen) TimeDialog(f.occurredAt, onDaysAgo = vm::setDaysAgo, onChange = vm::setTime, onDismiss = { timeOpen = false })
}

enum class AccountPickTarget { OWN, FROM, TO }

private val BillTab.label: Int get() = when (this) {
    BillTab.EXPENSE -> R.string.bill_expense
    BillTab.INCOME -> R.string.bill_income
    BillTab.TRANSFER -> R.string.bill_transfer
}

private fun currentField(f: BillForm): BillExpression = when (f.target) {
    BillField.AMOUNT -> f.amount
    BillField.DISCOUNT -> f.discount
    BillField.FEE -> f.fee
    BillField.TO_AMOUNT -> f.toAmount
    BillField.ACCOUNT_AMOUNT -> f.accountAmount ?: BillExpression()
    BillField.RATE -> BillExpression()
}

/** A figure the keypad can type into; outlined while it has the keypad. */
@Composable internal fun FigureBox(label: String, text: String, focused: Boolean, onFocus: () -> Unit, modifier: Modifier = Modifier,
    large: Boolean = false, tag: String, leading: (@Composable () -> Unit)? = null, expression: String? = null) {
    val colors = MaterialTheme.colorScheme
    // Leaving a text field for a figure puts the system keyboard away, so the bill keypad comes back.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusFigure = { focusManager.clearFocus(); keyboard?.hide(); onFocus() }
    GlassSurface(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .then(if (focused) Modifier.border(BorderStroke(1.5.dp, colors.primary), RoundedCornerShape(18.dp)) else Modifier)
        .clickable(role = Role.Button, onClick = focusFigure).semantics { selected = focused }.testTag(tag),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = if (large) 14.dp else 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            if (leading != null) leading() else Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(text.ifEmpty { "0" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = (if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium)
                        .copy(fontFeatureSettings = "tnum", fontWeight = if (large) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (text.isEmpty()) colors.onSurfaceVariant else colors.onSurface)
                if (!expression.isNullOrEmpty()) Text(expression, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun amountText(e: BillExpression, digits: Int): String =
    e.value(digits)?.let { DecimalRules.format_display(it, digits) } ?: if (e.isEmpty) "" else e.text

@Composable private fun SpendingFields(state: BillEditorState, vm: BillEditorViewModel, onManageCategories: () -> Unit) {
    val f = state.form
    val p = state.preview
    val digits = p.currency?.fraction_digits ?: 2
    FigureBox(stringResource(R.string.bill_amount), amountText(f.amount, digits), f.target == BillField.AMOUNT,
        { vm.focus(BillField.AMOUNT) }, large = true, tag = "bill-amount",
        expression = f.amount.text.takeIf { f.amount.hasOperator },
        leading = {
            CurrencyChoice(p.currency?.code ?: "", vm::chooseCurrency, enabled = true,
                options = Currency.options(interfaceLanguage())) { open ->
                TextButton(open, Modifier.testTag("bill-currency")) {
                    Text(p.currency?.code ?: "—", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(4.dp)); FaIcon(FaIcons.chevronDown, null, Modifier.size(10.dp))
                }
            }
        })
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        DiscountMode.entries.forEach { mode ->
            FilterChip(f.discountMode == mode, { vm.setDiscountMode(mode) }, { Text(stringResource(
                if (mode == DiscountMode.DISCOUNT) R.string.bill_discount else R.string.bill_paid)) },
                Modifier.testTag("bill-discount-mode-${mode.name}"))
        }
        FigureBox("", amountText(f.discount, digits), f.target == BillField.DISCOUNT, { vm.focus(BillField.DISCOUNT) },
            Modifier.weight(1f), tag = "bill-discount", leading = {})
    }
    if (p.crossCurrency) ConversionBar(state, vm)
    CategoryGrid(state, vm, onManageCategories)
}

/** "1 USD = 7.1234 CNY" and the amount the account moved; either can be typed for this bill. */
@Composable private fun ConversionBar(state: BillEditorState, vm: BillEditorViewModel) {
    val f = state.form
    val p = state.preview
    val transfer = f.tab == BillTab.TRANSFER
    val from = (if (transfer) p.accountCurrency else p.currency) ?: return
    val to = (if (transfer) p.toCurrency else p.accountCurrency) ?: return
    val colors = MaterialTheme.colorScheme
    GlassSurface(Modifier.fillMaxWidth().testTag("bill-conversion"), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.missingRate) Text(stringResource(R.string.bill_rate_missing, from.code, to.code), color = colors.error,
                style = MaterialTheme.typography.bodySmall)
            val rateShown = p.rate?.let { if (f.rateInverted) BigDecimal.ONE.divide(it, 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros() else it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(if (f.rateInverted) "1 ${to.code} =" else "1 ${from.code} =", style = MaterialTheme.typography.bodyMedium)
                FigureBox("", rateShown?.toPlainString() ?: "", f.target == BillField.RATE, { vm.focus(BillField.RATE) },
                    Modifier.weight(1f), tag = "bill-rate", leading = {})
                Text(if (f.rateInverted) from.code else to.code, style = MaterialTheme.typography.bodyMedium)
                IconButton(vm::flipRateDirection, Modifier.testTag("bill-rate-flip")) {
                    FaIcon(FaIcons.rotate, stringResource(R.string.bill_rate_flip), Modifier.size(16.dp))
                }
            }
            if (!transfer) FigureBox(stringResource(R.string.bill_account_amount, to.code),
                f.accountAmount?.let { amountText(it, to.fraction_digits) } ?: p.convertedMinor?.let { plainAmount(it, to.fraction_digits) } ?: "",
                f.target == BillField.ACCOUNT_AMOUNT, { vm.focus(BillField.ACCOUNT_AMOUNT) }, tag = "bill-account-amount")
            if (f.rate != null || f.accountAmount != null) TextButton(vm::resetRate,
                Modifier.align(Alignment.End).testTag("bill-rate-reset")) { Text(stringResource(R.string.bill_rate_reset)) }
        }
    }
}

/**
 * Top-level categories of the tab's kind in a grid. The chosen one opens its subcategories in a box right under its
 * own row, between that row and the next.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CategoryGrid(state: BillEditorState, vm: BillEditorViewModel, onManageCategories: () -> Unit) {
    val f = state.form
    val kind = if (f.tab == BillTab.INCOME) BillCategoryKind.INCOME else BillCategoryKind.EXPENSE
    // A hidden category is offered only while the bill already uses it.
    val tops = state.categories.filter { it.kind == kind && it.parentId == null && (!it.hidden || it.id == f.categoryId) }
    val subs = state.categories.filter { it.parentId == f.categoryId && f.categoryId != null && (!it.hidden || it.id == f.subCategoryId) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        (tops.map<BillCategory, BillCategory?> { it } + null).chunked(CATEGORY_COLUMNS).forEach { row ->
            TileRow(row) { category ->
                if (category == null) CategoryTile(stringResource(R.string.bill_manage_categories), "ellipsis", "grey",
                    false, onManageCategories, "bill-manage-categories")
                else CategoryTile(categoryName(category), category.iconKey, category.colorKey, category.id == f.categoryId,
                    { vm.chooseCategory(category.id, null) }, "bill-category-${category.builtinKey ?: category.id}")
            }
            if (subs.isNotEmpty() && row.any { it != null && it.id == f.categoryId }) {
                // The subcategories in the same grid as their parents, in a box right under the parent's row.
                GlassSurface(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("bill-subcategories"),
                    shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        subs.chunked(CATEGORY_COLUMNS).forEach { line ->
                            TileRow(line) { sub ->
                                val selected = sub.id == f.subCategoryId
                                CategoryTile(categoryName(sub), sub.iconKey, sub.colorKey, selected,
                                    { vm.chooseCategory(f.categoryId!!, if (selected) null else sub.id) },
                                    "bill-sub-${sub.builtinKey ?: sub.id}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val CATEGORY_COLUMNS = 5

/** [CATEGORY_COLUMNS] equal cells; a short last row keeps its cells the same width. */
@Composable private fun <T> TileRow(items: List<T>, tile: @Composable (T) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        items.forEach { item -> Box(Modifier.weight(1f)) { tile(item) } }
        repeat(CATEGORY_COLUMNS - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable private fun CategoryTile(name: String, icon: String, color: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(horizontal = 2.dp, vertical = 4.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CategoryIcon(icon, color, size = 36.dp, selected = selected)
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A transfer side: [label], the account chosen and, under it, [balance] (its currency and balance) when there is one. */
@Composable private fun AccountChip(label: String, value: String, balance: String?, onClick: () -> Unit, tag: String) {
    GlassSurface(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(value, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
                if (balance != null) Text(balance, Modifier.testTag("$tag-balance"), textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp)); FaIcon(FaIcons.chevronRight, null, Modifier.size(10.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TransferFields(state: BillEditorState, accounts: Map<Long, BillAccountChoice>,
    vm: BillEditorViewModel, onPick: (AccountPickTarget) -> Unit) {
    val f = state.form
    val p = state.preview
    val external = stringResource(R.string.bill_external_account)
    val from = if (f.fromExternal) null else f.cashAccountId?.let(accounts::get)
    val to = if (f.toExternal) null else f.toCashAccountId?.let(accounts::get)
    AccountChip(stringResource(R.string.bill_transfer_from), if (f.fromExternal) external
        else from?.label() ?: stringResource(R.string.bill_choose_account), from?.let { accountBalanceText(it) },
        { onPick(AccountPickTarget.FROM) }, "bill-transfer-from")
    AccountChip(stringResource(R.string.bill_transfer_to), if (f.toExternal) external
        else to?.label() ?: stringResource(R.string.bill_choose_account), to?.let { accountBalanceText(it) },
        { onPick(AccountPickTarget.TO) }, "bill-transfer-to")
    if (f.fromExternal || f.toExternal) GlassTextField(f.counterparty, vm::setCounterparty, stringResource(R.string.bill_counterparty),
        Modifier.fillMaxWidth().testTag("bill-counterparty"))
    val digits = p.accountCurrency?.fraction_digits ?: 2
    FigureBox(stringResource(if (p.crossCurrency) R.string.bill_transfer_out_amount else R.string.bill_amount),
        amountText(f.amount, digits), f.target == BillField.AMOUNT, { vm.focus(BillField.AMOUNT) }, large = true, tag = "bill-amount",
        leading = { Text(p.accountCurrency?.code ?: "—", style = MaterialTheme.typography.titleMedium) },
        expression = f.amount.text.takeIf { f.amount.hasOperator })
    if (p.crossCurrency) {
        FigureBox(stringResource(R.string.bill_transfer_in_amount), amountText(f.toAmount, p.toCurrency?.fraction_digits ?: 2),
            f.target == BillField.TO_AMOUNT, { vm.focus(BillField.TO_AMOUNT) }, tag = "bill-to-amount",
            leading = { Text(p.toCurrency?.code ?: "—", style = MaterialTheme.typography.titleMedium) })
        ConversionBar(state, vm)
    }
    if (!f.fromExternal) FigureBox(stringResource(R.string.bill_fee), amountText(f.fee, digits), f.target == BillField.FEE,
        { vm.focus(BillField.FEE) }, tag = "bill-fee")
}

/**
 * Always in the same place above the keypad: what is missing (after a confirm), the pills, the note and the notes used
 * before with this category.
 */
@Composable private fun BottomBlock(state: BillEditorState, accounts: Map<Long, BillAccountChoice>, vm: BillEditorViewModel,
    onAccount: () -> Unit, onTime: () -> Unit, pictures: PicturePicker) {
    val f = state.form
    // The side padding of the scrolling rows sits inside the scroll, so they scroll out to the screen edges.
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(horizontal = 16.dp)) { ProblemLine(state) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("bill-pills"),
            horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            if (f.tab != BillTab.TRANSFER) Pill(f.cashAccountId?.let { id -> accounts[id]?.label() }
                ?: stringResource(R.string.bill_no_account), selected = f.cashAccountId != null, onClick = onAccount, tag = "bill-account")
            Pill(timeText(f.occurredAt), selected = false, onClick = onTime, tag = "bill-time")
            if (f.tab != BillTab.TRANSFER) Pill(stringResource(if (f.countInStats) R.string.bill_counted_pill else R.string.bill_not_counted_pill),
                selected = f.countInStats, onClick = { vm.setCountInStats(!f.countInStats) }, tag = "bill-count-in-stats")
            if (f.tab == BillTab.EXPENSE) Pill(stringResource(if (f.reimbursable) R.string.bill_reimbursable_pill else R.string.bill_not_reimbursable_pill),
                selected = f.reimbursable, onClick = { vm.setReimbursable(!f.reimbursable) }, tag = "bill-reimbursable")
            // Pictures are used less often than the rest, so they come last.
            if (pictures.available) Box {
                Pill(if (f.images.isEmpty()) stringResource(R.string.bill_images_pill)
                    else stringResource(R.string.bill_images_count, f.images.size, BillImageRules.MAX_PER_BILL),
                    selected = f.images.isNotEmpty(), onClick = { pictures.menu = true }, tag = "bill-images-pill")
                PictureMenu(pictures)
            }
        }
        // Who pays an expense back sits beside the note, so the block keeps its height.
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            NoteBox(f.note, vm::setNote, stringResource(R.string.bill_note), "bill-note", Modifier.weight(1f))
            if (f.tab == BillTab.EXPENSE && f.reimbursable)
                NoteBox(f.payer, vm::setPayer, stringResource(R.string.bill_payer), "bill-payer", Modifier.width(120.dp))
        }
        // Room for the notes used before, so the box does not jump when they appear.
        Row(Modifier.fillMaxWidth().height(22.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.md), verticalAlignment = Alignment.CenterVertically) {
            state.recentNotes.filter { it != f.note }.forEach { note ->
                Text(note, Modifier.widthIn(max = 160.dp).clip(RoundedCornerShape(6.dp)).clickable { vm.setNote(note) }
                    .padding(horizontal = 4.dp).testTag("bill-recent-note"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun timeText(time: java.time.LocalDateTime): String {
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> stringResource(R.string.bill_today)
        today.minusDays(1) -> stringResource(R.string.bill_yesterday)
        today.minusDays(2) -> stringResource(R.string.bill_day_before)
        else -> time.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    val future = if (time.toLocalDate().isAfter(today)) " · " + stringResource(R.string.bill_future) else ""
    return "$day ${time.toLocalTime().format(rememberClockFormatter())}$future"
}

/** A capsule as wide as its text, one height whatever script the text is in (Latin and CJK lines differ). */
@Composable internal fun Pill(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
        .clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }
        .padding(horizontal = 12.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant)
    }
}

/** A low, single-line text box: the note, or who pays an expense back. */
@Composable internal fun NoteBox(value: String, onChange: (String) -> Unit, hint: String, tag: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
    BasicTextField(value, onChange, modifier.height(36.dp).testTag(tag)
        .semantics { contentDescription = hint }, singleLine = true,
        textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainer)
                .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, style = style, color = colors.onSurfaceVariant)
                field()
            }
        })
}

/** What still needs choosing, shown once confirm has been pressed. */
@Composable private fun ProblemLine(state: BillEditorState) {
    val problem = state.preview.problem
    val text = state.error?.let { errorText(it.name) } ?: if (!state.attempted || problem == null) null else stringResource(when (problem) {
        BillProblem.DISCOUNT -> R.string.bill_problem_discount
        BillProblem.ACCOUNT -> R.string.bill_problem_account
        BillProblem.CATEGORY -> R.string.bill_problem_category
        BillProblem.RATE -> R.string.bill_problem_rate
        BillProblem.SAME_ACCOUNT -> R.string.bill_problem_same_account
        BillProblem.BOTH_EXTERNAL -> R.string.bill_problem_both_external
        BillProblem.DIVIDE -> R.string.bill_problem_divide
        BillProblem.AMOUNT -> R.string.bill_problem_amount
    })
    if (text != null) Text(text, Modifier.testTag("bill-problem"), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
}

/** The keypad: digits, the four operations, delete (hold to clear), record another, and confirm. */
@Composable fun BillKeypad(confirmLabel: String, confirmEnabled: Boolean, againEnabled: Boolean,
    onKey: (BillKey) -> Unit, onConfirm: () -> Unit, onAgain: () -> Unit, bottomSpace: Dp = 0.dp) {
    val rows = listOf(
        listOf(BillKey.D1, BillKey.D2, BillKey.D3, BillKey.PLUS),
        listOf(BillKey.D4, BillKey.D5, BillKey.D6, BillKey.MINUS),
        listOf(BillKey.D7, BillKey.D8, BillKey.D9, BillKey.TIMES),
        listOf(BillKey.POINT, BillKey.D0, BillKey.D00, BillKey.DIVIDE))
    val againLabel = stringResource(R.string.bill_again)
    val deleteLabel = stringResource(R.string.bill_key_delete)
    GlassSurface(Modifier.fillMaxWidth().testTag("bill-keypad"), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottomSpace)) {
            Column(Modifier.weight(4f)) {
                rows.forEach { row ->
                    Row { row.forEach { key -> KeyButton(key.symbol, Modifier.weight(1f), onClick = { onKey(key) }, tag = "bill-key-${key.name}") } }
                }
            }
            Column(Modifier.weight(1f)) {
                KeyButton("⌫", Modifier.fillMaxWidth(), description = deleteLabel, onClick = { onKey(BillKey.BACKSPACE) },
                    onLongClick = { onKey(BillKey.CLEAR) }, tag = "bill-key-BACKSPACE")
                KeyButton(againLabel, Modifier.fillMaxWidth(), enabled = againEnabled, onClick = onAgain, small = true, tag = "bill-again")
                KeyButton(confirmLabel, Modifier.fillMaxWidth(), height = 112.dp, enabled = confirmEnabled, primary = true,
                    onClick = onConfirm, tag = "bill-confirm")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun KeyButton(label: String, modifier: Modifier, height: Dp = 56.dp, enabled: Boolean = true,
    primary: Boolean = false, small: Boolean = false, description: String? = null, onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit, tag: String) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.height(height).padding(3.dp).clip(RoundedCornerShape(14.dp))
        .background(if (primary && enabled) colors.primary else colors.surfaceContainerHigh.copy(alpha = if (enabled) 0.7f else 0.35f))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        .semantics { if (description != null) contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(label, style = if (small) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge,
            color = when { primary && enabled -> colors.onPrimary; enabled -> colors.onSurface; else -> colors.onSurfaceVariant },
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Today, yesterday and the day before at a tap, or any date and time. */
@Composable internal fun TimeDialog(value: java.time.LocalDateTime, onDaysAgo: (Long) -> Unit,
    onChange: (java.time.LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    AnimatedGlassDialog(true, onDismiss) {
        DialogCard(stringResource(R.string.bill_time), Modifier.padding(16.dp), buttons = {
            TextButton(onDismiss, Modifier.testTag("bill-time-done")) { Text(stringResource(R.string.bill_done)) }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf(0L to R.string.bill_today, 1L to R.string.bill_yesterday, 2L to R.string.bill_day_before).forEach { (days, label) ->
                    val selected = value.toLocalDate() == LocalDate.now().minusDays(days)
                    FilterChip(selected, { onDaysAgo(days) }, { Text(stringResource(label)) }, Modifier.testTag("bill-days-ago-$days"))
                }
            }
            DateField(stringResource(R.string.bill_date), value.toLocalDate().toString(), { day ->
                onChange(java.time.LocalDateTime.of(LocalDate.parse(day), value.toLocalTime())) })
            TimeField(stringResource(R.string.bill_clock), value.toLocalTime().toString(), { time ->
                onChange(java.time.LocalDateTime.of(value.toLocalDate(), LocalTime.parse(time))) })
        }
    }
}

/** What the form needs to pick pictures: whether pictures can be added here, the source menu and the pickers. */
internal class PicturePicker {
    var available = false
    var room = 0
    var fromPhotos: () -> Unit = {}
    var takePhoto: () -> Unit = {}
    var menu by mutableStateOf(false)
    var noCamera by mutableStateOf(false)
}

/**
 * The photo picker (several at once while there is room for more than one) and the system camera, which needs no
 * camera permission. The camera writes to a file in the cache, kept across the app being ended, read and then deleted.
 */
@Composable private fun rememberPicturePicker(state: BillEditorState, vm: BillEditorViewModel): PicturePicker {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val room = BillImageRules.MAX_PER_BILL - state.form.images.size - state.imagesWorking
    fun read(uri: android.net.Uri): suspend () -> ByteArray = { BillImageCodec.prepare(resolver, uri) }
    val several = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room.coerceAtLeast(2))) { uris ->
        vm.addPictures(uris.map(::read))
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addPictures(listOf(read(it))) } }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (file != null) {
            if (taken) vm.addPictures(listOf { try { BillImageCodec.prepare(resolver, android.net.Uri.fromFile(file)) } finally { file.delete() } })
            else file.delete()
        }
    }
    val picker = remember { PicturePicker() }
    picker.available = LocalBillImages.current != null
    picker.room = room
    picker.fromPhotos = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (room >= 2) several.launch(request) else if (room == 1) one.launch(request)
    }
    picker.takePhoto = {
        if (room > 0) {
            val dir = File(context.cacheDir, "bill_camera").apply { mkdirs() }
            val file = File(dir, "${java.util.UUID.randomUUID()}.jpg")
            cameraFile = file.path
            try {
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.billcamera", file))
            } catch (_: ActivityNotFoundException) {
                cameraFile = null
                file.delete()
                picker.noCamera = true
            }
        }
    }
    return picker
}

/** Where a picture comes from: the photo picker or the camera. */
@Composable private fun PictureMenu(pictures: PicturePicker) {
    DropdownMenu(pictures.menu, { pictures.menu = false }) {
        if (pictures.room <= 0) Text(stringResource(R.string.bill_images_full, BillImageRules.MAX_PER_BILL),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenuItem({ Text(stringResource(R.string.bill_image_from_photos)) }, { pictures.menu = false; pictures.fromPhotos() },
            Modifier.testTag("bill-image-from-photos"), enabled = pictures.room > 0)
        DropdownMenuItem({ Text(stringResource(R.string.bill_image_take_photo)) }, { pictures.menu = false; pictures.takePhoto() },
            Modifier.testTag("bill-image-take-photo"), enabled = pictures.room > 0)
    }
    if (pictures.noCamera) ValnookDialog({ pictures.noCamera = false },
        confirmButton = { TextButton({ pictures.noCamera = false }) { Text(stringResource(R.string.bill_done)) } },
        text = { Text(stringResource(R.string.bill_image_no_camera)) })
}

/** The form's pictures: tap to view, the cross to take one off, the last tile to add more while there is room. */
@Composable private fun EditorImages(state: BillEditorState, vm: BillEditorViewModel, onAdd: () -> Unit) {
    val f = state.form
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    // The row sits at the end of the form; a picture just picked brings it into view.
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val shown = f.images.size + state.imagesWorking
    var seen by remember { mutableIntStateOf(0) }
    LaunchedEffect(shown) { if (shown > seen) bring.bringIntoView(); seen = shown }
    Column(Modifier.bringIntoViewRequester(bring), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.testTag("bill-editor-images"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            f.images.forEachIndexed { index, image ->
                Box(Modifier.size(64.dp)) {
                    BillImageThumb(image.key, 64.dp, { viewing = index }, Modifier.testTag("bill-editor-image-$index"))
                    Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape)
                        .background(colors.scrim.copy(alpha = 0.6f)).clickable { vm.removeImage(image.key) }
                        .testTag("bill-editor-image-remove-$index").semantics { contentDescription = "remove" },
                        contentAlignment = Alignment.Center) {
                        FaIcon(FaIcons.xmark, stringResource(R.string.bill_image_remove), Modifier.size(10.dp), tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
            repeat(state.imagesWorking) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh)
                    .testTag("bill-editor-image-working"), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (f.images.size + state.imagesWorking < BillImageRules.MAX_PER_BILL)
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHigh)
                    .clickable(onClick = onAdd).testTag("bill-editor-image-add"), contentAlignment = Alignment.Center) {
                    FaIcon(FaIcons.plus, stringResource(R.string.bill_image_add), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
        }
        if (state.imageFailed) Text(stringResource(R.string.bill_image_failed), Modifier.testTag("bill-image-failed"),
            style = MaterialTheme.typography.bodySmall, color = colors.error)
    }
    viewing?.let { start -> BillImageViewer(f.images.map { it.key }, start) { viewing = null } }
}
