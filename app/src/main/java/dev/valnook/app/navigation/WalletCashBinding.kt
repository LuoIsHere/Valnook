package dev.valnook.app.navigation

import dev.valnook.feature.cash.CashDayGroup
import dev.valnook.feature.cash.CashMonthEmpty
import dev.valnook.feature.cash.CashWalletSummary
import dev.valnook.feature.wallet.WalletCashSlots

/** Wallet detail reuses the cash ledger UI through app wiring, keeping feature modules independent. */
internal val walletCashSlots = WalletCashSlots(
    balanceSummary = { account, parentName, trend, onEdit, modifier, allAccounts, accountLabels ->
        CashWalletSummary(account, parentName, trend, onEdit, allAccounts, accountLabels, modifier)
    },
    dayGroup = { date, entries, onOpen -> CashDayGroup(date, entries, onOpen) },
    emptyMonth = { modifier -> CashMonthEmpty(modifier) }
)
