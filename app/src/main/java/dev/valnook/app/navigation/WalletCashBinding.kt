package dev.valnook.app.navigation

import dev.valnook.feature.cash.CashBalanceSummary
import dev.valnook.feature.cash.CashEntryItem
import dev.valnook.feature.wallet.WalletCashSlots

/** Wallet detail reuses the cash ledger UI through app wiring, keeping feature modules independent. */
internal val walletCashSlots = WalletCashSlots(
    balanceSummary = { account, onEdit, modifier, allAccounts, accountLabels ->
        CashBalanceSummary(account, onEdit, modifier, allAccounts, accountLabels)
    },
    entryItem = { entry, showDivider, onOpen -> CashEntryItem(entry, showDivider, onOpen) }
)
