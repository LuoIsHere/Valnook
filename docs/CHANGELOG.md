# Changelog

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
