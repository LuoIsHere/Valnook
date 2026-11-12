package dev.valnook.feature.bills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.BillIcons
import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.TransferKind
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.BillAccountChoice

/** Each category colour as (light, dark) so icons keep their contrast in both themes. */
private val palette = mapOf(
    "red" to (Color(0xFFD64545) to Color(0xFFFF8A80)),
    "orange" to (Color(0xFFE07B28) to Color(0xFFFFB067)),
    "amber" to (Color(0xFFC79200) to Color(0xFFFFD54F)),
    "green" to (Color(0xFF2E8B57) to Color(0xFF7FD8A2)),
    "teal" to (Color(0xFF14877F) to Color(0xFF6FD6CC)),
    "cyan" to (Color(0xFF1385A8) to Color(0xFF7CD0EA)),
    "blue" to (Color(0xFF3466C8) to Color(0xFF8FB1FF)),
    "indigo" to (Color(0xFF5A4FC4) to Color(0xFFA9A1FF)),
    "purple" to (Color(0xFF8A47B8) to Color(0xFFD3A6F2)),
    "pink" to (Color(0xFFC8457E) to Color(0xFFF79BC2)),
    "brown" to (Color(0xFF8D6346) to Color(0xFFD6B396)),
    "grey" to (Color(0xFF6E6E76) to Color(0xFFB9B9C2)),
)

@Composable internal fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable fun billColor(key: String): Color {
    val pair = palette[key] ?: palette.getValue("grey")
    return if (isDarkTheme()) pair.second else pair.first
}

/** The category's icon on a soft disc of its colour; a filled disc when [selected]. */
@Composable fun CategoryIcon(iconKey: String, colorKey: String, modifier: Modifier = Modifier, size: Dp = 40.dp,
    selected: Boolean = false) {
    val color = billColor(colorKey)
    Box(modifier.size(size).background(if (selected) color else color.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(BillIcons.resource(iconKey)), contentDescription = null, modifier = Modifier.size(size * 0.48f),
            tint = if (selected) (if (isDarkTheme()) Color(0xFF16161A) else Color.White) else color)
    }
}

/** Whether names follow English, the app's own language as resolved for this screen. */
@Composable internal fun englishNames(): Boolean = LocalConfiguration.current.locales[0].language != "zh"

@Composable fun categoryName(category: BillCategory?): String =
    category?.name(englishNames())?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.bill_category_missing)

/** "Account · sub-account", or the sub-account alone when it carries the account's name. */
fun BillAccountChoice.label(): String =
    if (cashAccountName.isBlank() || cashAccountName == accountName) accountName else "$accountName · $cashAccountName"

/** A signed amount as the list shows it: "−35.00" for money out, "+12.00" for money in. */
fun signedAmount(minor: Long, fractionDigits: Int, out: Boolean): String =
    (if (out) "−" else "+") + DecimalRules.format_display(minor, fractionDigits)

fun plainAmount(minor: Long, fractionDigits: Int): String = DecimalRules.format_display(minor, fractionDigits)

/** Whether a bill takes money out of the user's own accounts in the list's eyes. */
val Bill.isOutflow: Boolean get() = when (type) {
    BillType.EXPENSE -> true
    BillType.TRANSFER -> transferKind == TransferKind.TO_EXTERNAL
    else -> false
}

/** The amount a list row shows, in the account's currency: what the account moved. */
val Bill.shownMinor: Long get() = when {
    type == BillType.TRANSFER && transferKind == TransferKind.FROM_EXTERNAL -> toAmountMinor ?: accountAmountMinor
    type == BillType.TRANSFER -> accountAmountMinor + feeMinor
    else -> accountAmountMinor
}

val Bill.shownFractionDigits: Int get() =
    if (type == BillType.TRANSFER && transferKind == TransferKind.FROM_EXTERNAL) (toCurrency ?: accountCurrency).fraction_digits
    else accountCurrency.fraction_digits

val Bill.shownCurrencyCode: String get() =
    if (type == BillType.TRANSFER && transferKind == TransferKind.FROM_EXTERNAL) (toCurrency ?: accountCurrency).code
    else accountCurrency.code

/**
 * The name of one side of a bill: its sub-account, the outside party, or no account, which is either a deleted one
 * ([deleted]) or none at all.
 */
@Composable internal fun sideName(cashAccountId: Long?, accounts: Map<Long, BillAccountChoice>, external: Boolean,
    counterparty: String, deleted: Boolean = true): String = when {
    external -> counterparty.ifBlank { stringResource(R.string.bill_external_account) }
    cashAccountId == null -> stringResource(if (deleted) R.string.bill_deleted_account else R.string.bill_no_account)
    else -> accounts[cashAccountId]?.label() ?: stringResource(R.string.bill_deleted_account)
}

/** A transfer as "from → to". */
@Composable internal fun transferTitle(bill: Bill, accounts: Map<Long, BillAccountChoice>): String {
    val from = sideName(bill.cashAccountId, accounts, bill.transferKind == TransferKind.FROM_EXTERNAL, bill.counterparty)
    val to = sideName(bill.toCashAccountId, accounts, bill.transferKind == TransferKind.TO_EXTERNAL, bill.counterparty)
    return "$from → $to"
}
