# Changelog

## 0.0.18

- Valnook now runs on Android 13 or later (previously Android 16).
- Added automatic exchange rates from Frankfurter, off by default. When turned on, rates update once a day, statistics value each past day at that day's rate, and a notice appears when an update fails.
- Currencies without an exchange rate are now left out of totals and marked, instead of being counted at 1:1.
- Redesigned the account, sub-account, deposit, and account edit pages. Account pages show a three-month net assets trend, and balance changes are grouped by day with the balance after each change.
- Each instrument now has one page covering every account, with its current price, holdings by account, and trade history. Trades and dividends choose their account inside the form.
- Added dividends, which add to realized profit, and a realized profit breakdown by instrument.
- Added a built-in Uncategorized asset class. Asset classes and instruments with trades can now be deleted.
- The bottom navigation now shows icons with names and folds to the current page while scrolling.
- About now shows the version code, the MIT License, and third-party notices.
- Full backups now use data format 10. Backups created with 0.0.18 cannot be restored in earlier versions.
- Fixed a number of issues in backups, OneDrive, deposits, web management, and app performance. See the [full changelog](docs/CHANGELOG.md#0018) for details.
