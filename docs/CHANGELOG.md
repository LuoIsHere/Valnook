# Changelog

## 0.0.18

### Exchange rates
- Valnook now runs on Android 13 or later (previously Android 16).
- Added automatic exchange rates from Frankfurter in Settings > Exchange rates and during setup. It is off by default and shows what is sent and stored before it is turned on, with an option not to show this again.
- When automatic rates are on, rates update once a day on first opening the app, after changing the base currency, when turned on, or with Update now. A failed update is retried no sooner than 30 minutes later, and a notice at the top of the screen names the cause and the rates still in use.
- While automatic rates are on, the rates are read-only. Turning them off keeps the last retrieved rates for editing.
- Retrieved rates are kept as daily history on the device and included in full backups. Statistics value each past day at that day's rate and fall back to the current rate where no history exists. The automatic rate switch stays as set on each device and is not restored from backups.
- Currencies without an exchange rate are now left out of totals and marked as missing, in both automatic and manual modes, instead of being counted at 1:1.
- Base currency and exchange rates are now edited on one page, saved from the toolbar. Leaving the page with unsaved rates asks first.
- Currency names now follow the interface language.

### Accounts and cash
- Redesigned the account page. The header with the balance collapses while scrolling, a three-month net assets trend shows the value of any day you touch, and credit cards are listed above savings with their limit and billing details.
- Redesigned the account edit page as grouped settings rows.
- Sub-account pages collapse like the account page. Balance changes are grouped by day with the balance after each change and the time of each entry.
- A wallet card's sub-account page now uses the same layout, with the card's note shown under the balance.
- Ledger and trade times follow the system's 12- or 24-hour setting.

### Investments
- Each instrument now has one page covering every account. The page leads with the current price, its update date, and an Update price button, followed by the total value, unrealized profit, holdings by account, and the trade history by month with an account filter. Its price trend shows one price per day.
- Buy, sell, and dividend forms now include an account row that opens a list of the accounts that can take the trade.
- A new trade can also set its price as the instrument's current price.
- Added dividends. A dividend records the after-tax amount, can be added to a sub-account's cash, and counts toward realized profit without changing units or cost.
- Added a realized profit breakdown by instrument, opened from the Investments page.
- Added a built-in Uncategorized asset class. Deleting an asset class moves its instruments to Uncategorized.
- Instruments with trades can now be deleted after entering a confirmation code. The cash they moved is kept and labelled as belonging to a deleted instrument.
- All instruments and an account's holdings are now shown as single cards, with asset class filters and search.

### Deposits
- Redesigned the Deposits tab, the deposit form, and the deposit details.
- Fixed terms that start at the end of a month, such as 31 October to 30 April, not being recognised as six months.
- Changing a deposit's currency now clears a sub-account of the old currency and picks the new currency's only sub-account.

### Interface
- The bottom navigation now shows icons with names. It folds to the current page while a page scrolls down and opens again with a tap. Long names are kept whole.
- Redesigned the navigation settings page. Leaving it with unsaved changes asks first.
- Replaced the app's icons with Font Awesome Free icons.
- About now shows the version code beside the version name, the internal build, the full MIT License, and third-party notices.
- Setup now explains that a base currency is needed to finish, and that automatic rates can be turned on later.
- Wallet card back copy buttons now sit beside their text.
- Clarified wording throughout the app and web management in Chinese and English.
- Scrolling, the keyboard, and long lists are smoother, and statistics work only for the page on screen.

### Backups and OneDrive
- The OneDrive backup list is now kept on the device and shows when it was last refreshed. A backup removed elsewhere is reported and dropped from the list.
- Uploads whose check was interrupted are settled on the next backup, and copies left unverified for three days are moved to the OneDrive recycle bin.
- Fixed automatic backups stopping after an access or network error, and a scheduled backup being replaced when the app started.
- Local backups and Excel exports now report the file name as saved.
- Backups and exports no longer hold up editing while they are written.
- Full backups now use data format 10, which adds exchange rate history. Backups created with 0.0.18 cannot be restored in earlier versions; backups from earlier versions can still be restored.
- Removed the unused Google Drive code. Google Drive backup has been disabled since 0.0.17.

### Web management
- Added the Uncategorized class, cash moved by deleted instruments, and dividends, which are read-only.
- Pairing is more reliable and recovers after an interruption. Timeouts are reported, and record pages follow the filters that were applied.
- The live connection now opens with a one-time ticket instead of the session credential.

### Other fixes
- Fixed the cursor jumping when switching an amount between positive and negative.
- Fixed demo names staying in English after switching the language back to the system default.
- Fixed the last point of trend charts being cut in half.
- Fixed the open page and unsaved drafts being lost when Android closed the app in the background.
- Fixed the app keeping the time zone it started in after the device time zone changed.
- Fixed account photos replaced earlier being left on the device.
- Amounts in statistics and holdings are rounded and grouped like other amounts, and a value shown as zero has no sign or color.
- Updated the privacy policy for automatic exchange rates and current backup behavior.

## 0.0.17

- Added account groups. Main accounts can be sorted into named groups that fold and unfold with an animation on the Accounts page, with a total in each group header. A group manager creates, renames, reorders, and deletes groups and moves accounts between them.
- Added account groups to web management, and included them in full backups. Clear data removes them and leaves a fresh default group.
- Added a cardholder name of up to 26 characters to wallet card backs. Like the other card-back details, it is encrypted on the device and excluded from backups, exports, and web management.
- Added a card face library in Settings > Card faces. It keeps up to 50 named faces, including faces a card no longer uses, lets you save a face to a location you choose, and is included in full backups.
- Cards scrolled upward now stack under the toolbar. The navigation bar slides away when card details open and returns with the card, and card details glow with the card's edge colors as the card opens, flips, and collapses its header.
- Added an asset-type filter to All instruments in the app and in web management.
- Balance change lists now show a record's note in place of its source when a note exists.
- Backup times are shown in the device time zone with their UTC offset, and the latest cloud backup result shows why it failed.
- Made OneDrive backups faster by checking each upload with a single download and by listing the backup folder only once after a successful backup.
- Lined up instrument symbols on the left and names on the right when adding a holding in web management, with the currency shown beside the symbol.
- Redesigned the demo data around a typical personal ledger with ten years of history, available in Chinese and English. Demo text follows the app language.
- Full backups now use data format 9. Backups created with 0.0.17 cannot be restored in earlier versions; backups from earlier versions can still be restored.
- Fixed a crash on some devices when adjusting a card face right after choosing an image.
- Fixed the card-back editor so its color and save buttons clear the text focus and keyboard, and fixed the card editor's shared-subaccount notice not updating when the card list changed.
- Fixed gaps in net worth and investment value trends when trades were recorded before an instrument's first price. Changes are now measured from zero before history begins, and percentages are shown when the earlier value is zero or negative.
- Fixed holding costs so purchases are valued at the unrounded trade amount, avoiding average costs such as 69.29407 for a 69.294 purchase. Profit or loss smaller than one cent is no longer colored as a loss or shown as +0.00.
- Fixed web investment pages. Asset types now open a list where types can be viewed and renamed, choosing an instrument for a new holding no longer asks to discard changes, and accounts whose trades were all deleted no longer appear. Quantities, trade prices, and average costs show full precision, holdings with more than 100 trades show all of them, adding an instrument without an asset type explains that one is needed first, and statistics curves no longer jump from zero in the month history begins.
- Fixed the backup screen so local backup progress, results, restore previews, and errors appear above the OneDrive section. The cloud backup failure banner no longer covers the whole screen, and remaining "Drive" wording now refers to OneDrive.
- Fixed automatic backups being skipped, or retried indefinitely while waiting for a network, after data had changed since they were scheduled. A cancelled backup no longer leaves the status at Uploading.
- Fixed the on-screen keyboard covering input fields and buttons, such as the restore confirmation code and exchange-rate settings.
- Converted totals, including account totals, available cash, account details, and investment values, now use the same thousands separators as other amounts.
- Updated the OneDrive backup guide, replaced the Google Drive guide with a short notice that it is disabled, and updated the privacy policy.

## 0.0.16

- Added built-in wallet card backgrounds, geometric patterns, and customizable colors, with clearer horizontal scrolling in the artwork picker.
- Added subaccount balance sparklines and a daily balance chart covering the past three months. Historical values can be inspected by touch or drag.
- Refined charts with yellow lines in light mode, blue lines in dark mode, a touch guide with colored and gray segments, and a smooth reveal of both the curve and its filled area.
- Show zero before recorded history begins and extend current statistics into future dates with a horizontal dashed line, without treating future dates as recorded values.
- Added current-month available cash and investment value charts to the account and investment home screens, and moved the asset summary to Statistics.
- Updated statistics readouts with the selected value, daily or monthly change, and percentage where available.
- Redesigned balance editing with a larger amount field, the previous balance, the adjustment amount, and a separate note. Standardized account editing icons and grouped term-deposit actions.
- Added an account-specific closed-position entry and corrected empty investment layouts.
- Included built-in card designs in full backups while keeping private card-back data excluded. Updated demo data with 15 built-in card designs.
- Updated the English and Chinese documentation, screenshots, wallet animations, and privacy policy.

## 0.0.15

- Reorganized web account details into separate translucent subaccount cards, with properly wrapped notes and a single account editing entry point.
- Fixed subaccount name editing and separated existing subaccounts from the add-subaccount form. Newly created subaccounts appear at the end of the list.
- Set the LAN web management port to `8765`, with a clear error when the port is unavailable.
- Added softer transitions for web cards, charts, distribution bars, navigation, side panels, expandable sections, and notifications, with support for reduced motion.
- Reduced loading flicker, preserved existing content during refresh, and prevented outdated responses from replacing the active view.
- Made web side panels resizable by dragging their edge, with a shared width across panels during the page session.
- Made account editing return to the parent account panel after saving, cancelling, or closing. Deposit, holding, and trade panels now follow the same navigation hierarchy and preserve the parent scroll position.

## 0.0.14

- Added animated card flipping and editable card backs with a card number, expiry date, CVV1, CVV2, and configurable back and edge colors.
- Encrypted private card-back content locally using AES-GCM with keys managed by Android Keystore. This content is excluded from backups, exports, and web management, and is unavailable in demo mode.
- Added a privacy warning before editing and updated the privacy policy to explain local storage, backup exclusions, and the risks of storing card details.
- Added optional card-number spacing, support for up to 38 characters, and automatic two-line layout. Empty fields are hidden, and each CVV accepts up to four digits.
- Added copy actions with sensitive-clipboard handling and a confirmation that does not display the copied value.
- Protected private card screens from screenshots and cleared visible private content when the app moves to the background. Returning to the wallet flips the card to its front first.
- Preserved private content across normal restarts and card edits. Deleting a card or clearing all data removes its private content; a successful backup restore leaves restored card backs empty, while a failed restore preserves existing content.

## 0.0.13

- Added Wallet as a configurable navigation destination, with named cards, optional custom images, and optional links to existing savings or credit subaccounts.
- Added local image import with fixed-ratio cropping, zoom, and rotation. Added static JPEG, PNG, and WebP support for card images and account icons, including web account-icon uploads.
- Added a vertical card stack with animated selection and return, coordinated background-card movement, and a detail panel that slides up from below.
- Added drag sorting with haptic feedback, automatic order saving, and restoration of the wallet's browsing position during the app session.
- Added linked-account summaries and monthly funds activity, including credit limits and billing-date information. Cards reuse existing account data without duplicating balances or records.
- Added a collapsing card header while browsing activity and preserved the selected month when returning from record details.
- Included card images, account links, and ordering in full backups. Deleting a linked account keeps the card and removes its link; deleting a card leaves account data unchanged.
- Expanded demo data with approximately 15 sample cards.

## 0.0.12

- Added account display controls and the option to exclude selected savings subaccounts from available cash without changing total assets.
- Simplified balance editing and toolbar actions, and improved drag sorting stability.
- Added searchable investment-instrument selection and creation of a holding together with its first trade.
- Improved credit-account validation, including support for a zero credit limit.
- Added confirmed deletion of main accounts and subaccounts, with handling for linked cash records and shared credit limits.
- Added monthly record browsing and corrected month boundaries and historical statistics after account deletion.
- Updated backups, exports, web management, and demo data for the account changes.

## 0.0.11

- Added Microsoft Entra authentication support for release builds to enable Microsoft account sign-in for OneDrive backups.

## 0.0.10

- Added first-launch setup for privacy-policy acceptance, base currency, and optional exchange rates.
- Added language selection during setup and preserved entered values when switching languages.
- Added an About page with English and Chinese privacy policies and a link to the project repository.
- Refined glass inputs, dialogs, and navigation bars, including toolbar backgrounds that appear as content scrolls underneath.
- Added batch investment-price editing and collapsible investment-account groups in web management.

## 0.0.9

- Switched the cloud backup destination from Google Drive to the application's dedicated folder in OneDrive.
- Added Microsoft account sign-in for OneDrive backup and restore, while retaining automatic backup scheduling and backup retention controls.

## 0.0.8

- Introduced neutral glass styling, translucent asset summaries and navigation bars, and more compact secondary information.
- Added system, light, and dark theme settings, along with animated navigation capsules and smoother panel transitions.
- Added circular account icons using bundled Material Symbols or imported images, with image cropping and backup support.
- Standardized account names and icons across account and investment pages, with better handling of long names.
- Redesigned web management and fixed layout issues, failed saves, and duplicate icons.
- Kept the phone screen awake during web management and retained automatic disconnection after five minutes without user activity.
- Added the app logo to Android and web management.
- Fixed a flashing loading indicator when switching months in investment-value charts.

## 0.0.7

- Added credit subaccounts with independent or shared credit limits, outstanding debt, overpayments, available credit, and over-limit indicators.
- Added statement and payment dates with billing-calendar reminders.
- Included credit balances in net assets while excluding them from available cash and investment or deposit funding selections.
- Extended backups, exports, and web management to support credit accounts.
- Added account-detail tabs and persistent ordering for main accounts and subaccounts.
- Improved chart readouts and compact selection controls, and fixed press feedback extending beyond rounded component bounds.
- Expanded demo data with savings, credit, and brokerage examples.

## 0.0.6

- Added LAN web management for accounts, funds activity, investment instruments, holdings, trades, and term deposits, with read-only statistics.
- Added QR-code and pairing-code access, with one active browser management session and an app lock screen while that session is active.
- Added responsive web layouts with English and Chinese text and light and dark themes.
- Added record filtering and paginated browsing in web management.
- Improved chart layouts and adaptive axis scaling, and refined settings and cloud-backup controls.

## 0.0.5

- Added full local backup and restore, including archive validation before replacing existing data.
- Added Excel export with account summaries, assets, and records in English or Chinese.
- Added Google Drive backups with manual backup and restore, automatic scheduling, and retention of the latest five backups.
- Added cloud-account reconnection and disconnection controls, and paused automatic backups after a restore.

## 0.0.4

- Added daily and monthly charts for total assets, available cash, and investment value, plus the current month's asset change.
- Added configurable navigation order and visibility, with continued access to hidden pages.
- Added bilingual demo data with two years of financial history.
- Improved chart labels and selection behavior while scrolling.
- Corrected historical accounting and holding lifecycle calculations.

## 0.0.3

- Added independent cash subaccounts, including multiple subaccounts in the same currency.
- Linked trades and term deposits to specific cash subaccounts, with balance corrections when a link changes.
- Added negative cash balances and improved account and investment record views.
- Added English and Chinese interfaces, configurable gain/loss colors, and base-currency and exchange-rate settings.
- Added an isolated demo mode and confirmed data clearing, and fixed repeated activity recreation when switching languages.
- **Breaking change:** The new subaccount model resets financial data, base-currency settings, and exchange rates when upgrading from the previous database format.

## 0.0.2

- Added a shared investment-instrument library with common prices and asset types across accounts.
- Added consolidated asset, cash, and investment valuations using a base currency and manually configured exchange rates.
- Corrected moving-average cost calculations for partial sales, closed positions, and subsequent purchases, without rewriting previously realized gains.
- Improved linked cash adjustments when editing trades and term deposits, and clearly identified unavailable cost information.
- Added compact account layouts, floating navigation, and clearer holding and record details.

## 0.0.1

- Added account management with multicurrency cash balances and funds activity.
- Added investment instruments, holdings, buy and sell records, and realized and unrealized profit calculations.
- Added term-deposit creation and settlement with optional cash-account linkage.
- Added record details and editing for cash activity, trades, and term deposits, with corresponding balance updates.
