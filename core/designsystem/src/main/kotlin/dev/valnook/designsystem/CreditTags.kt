package dev.valnook.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R

/** What a credit account's balance means: owed below zero, a credit balance above it, nothing owed at zero. */
@Composable fun creditBalanceText(balanceMinor: Long): String = stringResource(when {
    balanceMinor < 0 -> R.string.credit_owed
    balanceMinor > 0 -> R.string.credit_overpaid
    else -> R.string.credit_no_debt
})

/** [creditBalanceText] as a tag, red when owed and green for a credit balance. */
@Composable fun CreditBalanceTag(balanceMinor: Long, modifier: Modifier = Modifier) =
    StatusTag(creditBalanceText(balanceMinor), when {
        balanceMinor < 0 -> TagTone.NEGATIVE
        balanceMinor > 0 -> TagTone.POSITIVE
        else -> TagTone.NEUTRAL
    }, modifier)

/** The next statement date, or the payment due date when [dueNext]: today, or in so many days. */
@Composable fun creditBillingText(daysRemaining: Long, dueNext: Boolean): String {
    val days = daysRemaining.toInt()
    return when {
        days == 0 && dueNext -> stringResource(R.string.credit_due_today)
        days == 0 -> stringResource(R.string.credit_statement_today)
        dueNext -> pluralStringResource(R.plurals.credit_due_in, days, days)
        else -> pluralStringResource(R.plurals.credit_statement_in, days, days)
    }
}
