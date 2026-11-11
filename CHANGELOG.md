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

## 0.0.17

- Added account groups. Main accounts can be sorted into named groups that fold and unfold on the Accounts page, managed from a group manager and from web management.
- Added a cardholder name to wallet card backs, encrypted on the device like the other card-back details.
- Added a card face library in Settings > Card faces that keeps up to 50 named faces and is included in full backups.
- Cards scrolled upward now stack under the toolbar, the navigation bar slides with card details, and card details glow with the card's edge colors.
- Added an asset-type filter to All instruments in the app and in web management.
- Balance change lists now show a record's note in place of its source.
- Backup times are shown in the device time zone, the latest cloud backup result shows why it failed, and OneDrive backups are faster.
- Redesigned the demo data in Chinese and English.
- Full backups now use data format 9. Backups created with 0.0.17 cannot be restored in earlier versions.
- Fixed a number of issues in Wallet, statistics, investments, backups, and web management. See the [full changelog](docs/CHANGELOG.md#0017) for details.
