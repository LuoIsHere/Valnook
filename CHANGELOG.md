# Changelog

## 0.0.19

- Added bookkeeping: expenses, income and transfers with 88 built-in categories, discounts, bills in other currencies at that day's rate, and bills kept on no account.
- Expenses can be refunded and marked to be paid back, with a Reimbursements page for what is still owed.
- Bills can hold up to 4 pictures from Photos or the camera. Pictures are backed up to OneDrive, can be cleared from the device by hand or automatically, and are fetched back when needed.
- Added optional AI bookkeeping, off by default. Connect an OpenAI-compatible service of your choice to turn a sentence, shared text or a screenshot into bill drafts, saved only after you confirm, and to ask questions about your bills.
- Added Quick bookkeeping: open to a new bill, record from the share menu, and app icon shortcuts.
- Statistics add Expense and Income tabs with a category ring and daily bars, and the Assets tab shows distribution by account, asset type and currency.
- Credit cards can be read by billing cycle.
- Redesigned web management, with a read-only Bills page and income and expense statistics.
- Buttons, text fields, switches, dialogs and bottom sheets now share one style, and Settings has been regrouped.
- Demo data now includes bills, and its dates move onto today when demo mode opens.
- Full backups now use data format 16. Backups created with 0.0.19 cannot be restored in earlier versions. Local backups do not include bill pictures.
- Fixed a number of issues in exchange rates, web management, Wallet and the interface. See the [full changelog](docs/CHANGELOG.md#0019) for details.
