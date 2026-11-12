package dev.valnook.feature.bills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.Currency
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The bill form: tabs for expense, income and transfer over the figures, the categories and the details, with the
 * bill keypad at the bottom. The keypad gives way to the system keyboard while a text field is in use.
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
    Column(Modifier.fillMaxSize().padding(bottom = if (keyboardOpen) 0.dp else LocalPageBottomSpace.current)
        .testTag("bill-editor")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Spacer(Modifier.height(LocalPageTopSpace.current))
            if (!state.editing || f.tab != BillTab.TRANSFER) CapsuleChoiceRow(
                // An edit stays the kind of bill it is between expense and income; a transfer stays a transfer.
                if (state.editing) BillTab.entries.filter { (it == BillTab.TRANSFER) == (f.tab == BillTab.TRANSFER) } else BillTab.entries,
                f.tab, vm::selectTab, optionModifier = { Modifier.testTag("bill-tab-${it.name}") }) { Text(stringResource(it.label)) }
            if (f.tab == BillTab.TRANSFER) TransferFields(state, accounts, vm, onPick = { picking = it })
            else SpendingFields(state, accounts, vm, onPick = { picking = AccountPickTarget.OWN }, onManageCategories)
            DetailsRow(state, vm, onTime = { timeOpen = true })
            NoteField(state, vm)
            ProblemHint(state)
            Spacer(Modifier.height(Space.sm))
        }
        if (!keyboardOpen) BillKeypad(
            confirmLabel = if (currentField(f).hasOperator) "=" else stringResource(if (state.editing) R.string.bill_save else R.string.bill_confirm),
            confirmEnabled = (currentField(f).hasOperator || p.problem == null) && state.phase != BillSavePhase.WORKING,
            againEnabled = !state.editing && p.problem == null && state.phase == BillSavePhase.IDLE,
            onKey = vm::press, onConfirm = { vm.confirm() }, onAgain = { vm.confirm(again = true) })
    }
    picking?.let { target ->
        BillAccountPicker(state.accounts,
            selected = when (target) { AccountPickTarget.TO -> f.toCashAccountId; else -> f.cashAccountId },
            allowExternal = target != AccountPickTarget.OWN,
            externalSelected = (target == AccountPickTarget.FROM && f.fromExternal) || (target == AccountPickTarget.TO && f.toExternal),
            onPick = { id ->
                when (target) {
                    AccountPickTarget.OWN -> id?.let(vm::chooseAccount)
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
@Composable private fun FigureBox(label: String, text: String, focused: Boolean, onFocus: () -> Unit, modifier: Modifier = Modifier,
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

private fun amountText(e: BillExpression, digits: Int): String =
    e.value(digits)?.let { DecimalRules.format_display(it, digits) } ?: if (e.isEmpty) "" else e.text

@Composable private fun SpendingFields(state: BillEditorState, accounts: Map<Long, dev.valnook.domain.repository.BillAccountChoice>,
    vm: BillEditorViewModel, onPick: () -> Unit, onManageCategories: () -> Unit) {
    val f = state.form
    val p = state.preview
    val digits = p.currency?.fraction_digits ?: 2
    FigureBox(stringResource(R.string.bill_amount), amountText(f.amount, digits), f.target == BillField.AMOUNT,
        { vm.focus(BillField.AMOUNT) }, large = true, tag = "bill-amount",
        expression = f.amount.text.takeIf { f.amount.hasOperator || f.amount.text.endsWith("+") },
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
    AccountChip(stringResource(R.string.bill_account), f.cashAccountId?.let(accounts::get)?.label()
        ?: stringResource(R.string.bill_choose_account), onPick, "bill-account")
}

/** "Paid USD 20.00 × 7.1234 = CNY 142.47": the rate and the account amount can each be typed for this bill. */
@Composable private fun ConversionBar(state: BillEditorState, vm: BillEditorViewModel) {
    val f = state.form
    val p = state.preview
    val transfer = f.tab == BillTab.TRANSFER
    val from = (if (transfer) p.accountCurrency else p.currency) ?: return
    val to = (if (transfer) p.toCurrency else p.accountCurrency) ?: return
    val colors = MaterialTheme.colorScheme
    val warning = p.missingRate
    GlassSurface(Modifier.fillMaxWidth().testTag("bill-conversion"), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (warning) Text(stringResource(R.string.bill_rate_missing, from.code, to.code), color = colors.error,
                style = MaterialTheme.typography.bodySmall)
            val rateShown = p.rate?.let { if (f.rateInverted) BigDecimal.ONE.divide(it, 12, java.math.RoundingMode.HALF_UP).stripTrailingZeros() else it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(if (f.rateInverted) "1 ${to.code} =" else "1 ${from.code} =", style = MaterialTheme.typography.bodyMedium)
                FigureBox("", rateShown?.toPlainString() ?: "", f.target == BillField.RATE, { vm.focus(BillField.RATE) },
                    Modifier.weight(1f), tag = "bill-rate", leading = {})
                Text(if (f.rateInverted) from.code else to.code, style = MaterialTheme.typography.bodyMedium)
                IconButton(vm::flipRateDirection, Modifier.semantics { contentDescription = "" }.testTag("bill-rate-flip")) {
                    FaIcon(FaIcons.rotate, stringResource(R.string.bill_rate_flip), Modifier.size(16.dp))
                }
            }
            if (!transfer) {
                val digits = to.fraction_digits
                FigureBox(stringResource(R.string.bill_account_amount, to.code),
                    f.accountAmount?.let { amountText(it, digits) } ?: p.convertedMinor?.let { plainAmount(it, digits) } ?: "",
                    f.target == BillField.ACCOUNT_AMOUNT, { vm.focus(BillField.ACCOUNT_AMOUNT) }, tag = "bill-account-amount")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(when (p.rateSource) {
                    dev.valnook.domain.model.BillRateSource.AUTO -> R.string.bill_rate_source_auto
                    dev.valnook.domain.model.BillRateSource.MANUAL -> R.string.bill_rate_source_manual
                    dev.valnook.domain.model.BillRateSource.DERIVED -> R.string.bill_rate_source_derived
                    dev.valnook.domain.model.BillRateSource.ONE_TIME -> R.string.bill_rate_source_once
                    else -> R.string.bill_rate_source_none
                }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (f.rate != null || f.accountAmount != null) TextButton(vm::resetRate, Modifier.testTag("bill-rate-reset")) {
                    Text(stringResource(R.string.bill_rate_reset))
                }
            }
            if (p.canSaveAsManual && (f.rate != null || f.accountAmount != null))
                CheckboxRow(stringResource(R.string.bill_rate_save_manual), f.saveAsManual, vm::setSaveAsManual)
        }
    }
}

/** Top-level categories of the tab's kind in a grid; the chosen one's subcategories in a row under it. */
@Composable private fun CategoryGrid(state: BillEditorState, vm: BillEditorViewModel, onManageCategories: () -> Unit) {
    val f = state.form
    val kind = if (f.tab == BillTab.INCOME) BillCategoryKind.INCOME else BillCategoryKind.EXPENSE
    // A hidden category is offered only while the bill already uses it.
    val tops = state.categories.filter { it.kind == kind && it.parentId == null && (!it.hidden || it.id == f.categoryId) }
    val subs = state.categories.filter { it.parentId == f.categoryId && f.categoryId != null && (!it.hidden || it.id == f.subCategoryId) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        BoxWithConstraints {
            val columns = (maxWidth / 72.dp).toInt().coerceIn(4, 6)
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                (tops.map<BillCategory, BillCategory?> { it } + null).chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { category ->
                            Box(Modifier.weight(1f)) {
                                if (category == null) CategoryTile(stringResource(R.string.bill_manage_categories), "ellipsis", "grey",
                                    false, onManageCategories, "bill-manage-categories")
                                else CategoryTile(categoryName(category), category.iconKey, category.colorKey, category.id == f.categoryId,
                                    { vm.chooseCategory(category.id, null) }, "bill-category-${category.builtinKey ?: category.id}")
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (subs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            subs.forEach { sub ->
                val selected = sub.id == f.subCategoryId
                FilterChip(selected, { vm.chooseCategory(f.categoryId!!, if (selected) null else sub.id) }, { Text(categoryName(sub)) },
                    Modifier.testTag("bill-sub-${sub.builtinKey ?: sub.id}"),
                    leadingIcon = { CategoryIcon(sub.iconKey, sub.colorKey, size = 22.dp, selected = selected) })
            }
        }
    }
}

@Composable private fun CategoryTile(name: String, icon: String, color: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CategoryIcon(icon, color, size = 44.dp, selected = selected)
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AccountChip(label: String, value: String, onClick: () -> Unit, tag: String) {
    GlassSurface(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).testTag(tag),
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Space.md))
            Text(value, Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(6.dp)); FaIcon(FaIcons.chevronRight, null, Modifier.size(10.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TransferFields(state: BillEditorState, accounts: Map<Long, dev.valnook.domain.repository.BillAccountChoice>,
    vm: BillEditorViewModel, onPick: (AccountPickTarget) -> Unit) {
    val f = state.form
    val p = state.preview
    val external = stringResource(R.string.bill_external_account)
    AccountChip(stringResource(R.string.bill_transfer_from), if (f.fromExternal) external
        else f.cashAccountId?.let(accounts::get)?.label() ?: stringResource(R.string.bill_choose_account),
        { onPick(AccountPickTarget.FROM) }, "bill-transfer-from")
    AccountChip(stringResource(R.string.bill_transfer_to), if (f.toExternal) external
        else f.toCashAccountId?.let(accounts::get)?.label() ?: stringResource(R.string.bill_choose_account),
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

@Composable private fun DetailsRow(state: BillEditorState, vm: BillEditorViewModel, onTime: () -> Unit) {
    val f = state.form
    val time = f.occurredAt
    val formatter = rememberClockFormatter()
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> stringResource(R.string.bill_today)
        today.minusDays(1) -> stringResource(R.string.bill_yesterday)
        else -> time.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    AccountChip(stringResource(R.string.bill_time), "$day ${time.toLocalTime().format(formatter)}" +
        if (time.toLocalDate().isAfter(today)) " · " + stringResource(R.string.bill_future) else "", onTime, "bill-time")
    if (f.tab != BillTab.TRANSFER) CheckboxRow(stringResource(R.string.bill_count_in_stats), f.countInStats, vm::setCountInStats,
        Modifier.testTag("bill-count-in-stats"))
}

@Composable private fun NoteField(state: BillEditorState, vm: BillEditorViewModel) {
    val f = state.form
    GlassTextField(f.note, vm::setNote, stringResource(R.string.bill_note), Modifier.fillMaxWidth().testTag("bill-note"))
    // Notes used before with this category, a tap away.
    val notes = state.recentNotes.filter { it != f.note }
    if (notes.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        notes.forEach { note -> SuggestionChip({ vm.setNote(note) }, { Text(note, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            Modifier.widthIn(max = 220.dp).testTag("bill-recent-note")) }
    }
}

@Composable private fun ProblemHint(state: BillEditorState) {
    val problem = state.preview.problem
    val shown = state.error != null || (problem != null && problem != BillProblem.AMOUNT)
    if (!shown) return
    val text = state.error?.let { errorText(it.name) } ?: stringResource(when (problem) {
        BillProblem.DISCOUNT -> R.string.bill_problem_discount
        BillProblem.ACCOUNT -> R.string.bill_problem_account
        BillProblem.CATEGORY -> R.string.bill_problem_category
        BillProblem.RATE -> R.string.bill_problem_rate
        BillProblem.SAME_ACCOUNT -> R.string.bill_problem_same_account
        BillProblem.BOTH_EXTERNAL -> R.string.bill_problem_both_external
        BillProblem.DIVIDE -> R.string.bill_problem_divide
        else -> R.string.bill_problem_amount
    })
    HintMessage(text, isError = state.error != null)
}

/** The keypad: digits, the four operations, delete (hold to clear), record another, and confirm. */
@Composable fun BillKeypad(confirmLabel: String, confirmEnabled: Boolean, againEnabled: Boolean,
    onKey: (BillKey) -> Unit, onConfirm: () -> Unit, onAgain: () -> Unit) {
    val rows = listOf(
        listOf(BillKey.D1, BillKey.D2, BillKey.D3, BillKey.PLUS, BillKey.BACKSPACE),
        listOf(BillKey.D4, BillKey.D5, BillKey.D6, BillKey.MINUS, null),
        listOf(BillKey.D7, BillKey.D8, BillKey.D9, BillKey.TIMES, null),
        listOf(BillKey.POINT, BillKey.D0, BillKey.D00, BillKey.DIVIDE, null))
    val againLabel = stringResource(R.string.bill_again)
    val deleteLabel = stringResource(R.string.bill_key_delete)
    GlassSurface(Modifier.fillMaxWidth().testTag("bill-keypad"), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(Modifier.padding(8.dp)) {
            Column(Modifier.weight(4f)) {
                rows.forEach { row ->
                    Row { row.take(4).forEach { key -> KeyButton(key!!.symbol, Modifier.weight(1f), onClick = { onKey(key) },
                        tag = "bill-key-${key.name}") } }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun KeyButton(label: String, modifier: Modifier, height: androidx.compose.ui.unit.Dp = 56.dp, enabled: Boolean = true,
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
@Composable private fun TimeDialog(value: java.time.LocalDateTime, onDaysAgo: (Long) -> Unit,
    onChange: (java.time.LocalDateTime) -> Unit, onDismiss: () -> Unit) {
    AnimatedGlassDialog(true, onDismiss) {
        GlassSurface(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Text(stringResource(R.string.bill_time), style = MaterialTheme.typography.titleMedium)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, Modifier.testTag("bill-time-done")) { Text(stringResource(R.string.bill_done)) }
                }
            }
        }
    }
}
