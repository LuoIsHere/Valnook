# Valnook Privacy Policy and Disclaimer

[简体中文](PRIVACY_POLICY_CN.md)

- Application: Valnook
- Developer: luoishere
- Contact: [xlll1314@outlook.com](mailto:xlll1314@outlook.com)
- Effective date: October 7, 2026
- Last updated: November 22, 2026
- Distribution: [Official GitHub repository](https://github.com/LuoIsHere/Valnook)

## 1. Scope

Valnook is a free, open-source tool for personal financial records and asset management, provided under the [MIT License](../LICENSE) and currently distributed only through the GitHub repository above. This policy applies to the Valnook application released by the developer and its local network Web administration feature. Providers of modified or redistributed versions are responsible for their versions' data practices.

Valnook primarily stores data locally. The developer does not operate a server to receive or store application user data, and the application does not upload your financial records, images, or backups to a developer-owned server. When you use cloud backup, file export, or Web administration, relevant data is transferred to your authorized service, chosen destination, or paired browser according to your actions or settings. When automatic exchange rates are on, the application sends only currency codes and dates to the rate service, never financial records; see Section 4. When you use AI features, what you enter or share is sent, as your settings allow, to the AI service address you entered yourself; see Section 5.

## 2. Information Processed and Its Purposes

| Category | Content and purpose |
| --- | --- |
| Financial records | Account names, account groups, notes, balances, currencies, credit limits and billing rules, deposits, instruments, holdings, trades, dividends, prices, exchange rates, and exchange rate history that you enter or import, as well as bills (amount, currency, discount, fee, category, time, note, payer, payee or transfer counterparty, and refund and reimbursement links) and bill categories, used for recording, calculations, statistics, and display. |
| Account images | Images you select through the system photo picker and crop for account icons, also included in full backups. |
| Bill pictures | Pictures you select through the system photo picker, take with the system camera, or share from other apps, attached to bills, up to 4 per bill. They are compressed on the device and saved as new WebP images, so location and other metadata from the original are not kept. The picture files are not included in full backups; with OneDrive connected they are uploaded separately, see Section 6. |
| AI settings | The AI service address, model, request parameters, default account and categories, nicknames, and My rules that you enter, used to send requests to the AI service you choose, and included in full backups. The API key, check results, avatars, conversations, and usage records are kept on this device only and are not included in full backups; see Section 5. |
| Preferences | Language, theme, gain/loss colors, navigation bar, ordering, and other display settings, as well as Quick bookkeeping settings (whether the app opens on a new bill and whether it appears in the system share menu), used to retain your preferences. The theme and Quick bookkeeping settings are kept on this device only; the other settings are included in full backups. |
| Automatic rate status | Whether automatic exchange rates are on, the times of the last success and the last attempt, the reason for a failure, and whether the network notice is hidden, used to decide when to connect and to show the update status. Kept on this device only; not saved in or restored from full backups. |
| Operation history | Contents before and after changes, operation times, and related information used for record consistency, historical review, and backup restoration. Some deleted records may remain in this history. |
| Cloud connection information | Microsoft account identifiers, display names, email addresses or account display information, authorization credentials, backup status, the list of cloud backups, and the upload status of bill pictures processed when connecting OneDrive, used for authentication and backup management. |
| Web session information | Local network connection addresses, browser identification, pairing and session credentials, and connection and activity times, used for authentication, session management, and access limits. |

In this policy, a "full backup" is a backup file made by Valnook from which data can be restored, whether saved locally or to OneDrive, as distinct from an Excel workbook made for analysis.

Core financial management does not require registering a Valnook account or connecting a cloud service. The application does not use financial records for advertising, marketing profiles, or sale.

Wallet card fronts can use built-in patterns and colors or images selected by you through the system photo picker. Static JPEG, PNG and WebP images are cropped, rotated, compressed and color-sampled on the device and saved as new WebP images, so location and other metadata from the original are not kept. Valnook does not perform OCR or automatically collect card numbers, expiry dates or CVV. We recommend using a blank card face or one with sensitive details covered. Cards may link to an existing subaccount without creating a new account or duplicating financial records. Images applied to a card and images you upload and name in Settings › Card face library are kept in the on-device card face library (up to 50). A face remains after a card changes face or is deleted, until you delete it from the library; a face in use cannot be deleted. Card-front images, card face library images and their names, built-in patterns and colors, links and order are included in full backups, both local and OneDrive. You can save a library image to a location you choose; Valnook does not manage saved files, and deleting the face or clearing data does not delete them. Wallet images are not exposed through Web administration. Deleting a card does not delete its linked subaccount or its face; clearing all data also removes Wallet data and the card face library.

Card backs are available only with your real data, not in demo mode. You may voluntarily enter a card number, cardholder name, expiry date, CVV1 and CVV2 and choose back and edge colors. These values use Android Keystore for key management and AES-GCM encryption and are stored separately in this device's private no-backup directory. They are never uploaded or included in local or OneDrive backups, never exported to Excel or other formats, and cannot be accessed through Web administration. They are excluded from financial audit history, logs and saved page state. The "Don't show again" choice in the risk notice also remains on this device only, and the notice shows again after all data is cleared. Only when you explicitly tap a copy icon is the corresponding number, cardholder name, expiry or CVV placed on the system clipboard and marked sensitive to limit system previews. The clipboard is outside this app's encrypted storage; the paste destination and software with appropriate access may receive it. Copy with care. The app never copies automatically, logs copied values or includes them in backups or file exports.

Normal restarts, app upgrades, renaming cards, changing front images, and changing or removing linked subaccounts preserve private back details. Deleting a card, clearing all data or successfully replacing data from a backup invalidates the local private association and removes its content. Failed backup validation or a rolled-back restore preserves the original details. After uninstalling, clearing the app's storage, or moving to another device, these details cannot be recovered, because backups never contain them. Front images remain included in full backups, including any text embedded in those images; the app does not automatically recognize or redact that text.

Disclosure of card numbers, cardholder names, expiry dates, CVV1 or CVV2 may cause financial loss. Enable your device's app lock for Valnook where available. System screenshots and display on non-secure screens are restricted while private back details or their editor are visible, and details are concealed when the app enters the background. Local encryption and window protection cannot guarantee absolute security on a compromised device.

**To the fullest extent permitted by applicable law, the developer is not liable for financial loss arising from disclosure of card details.** See Section 12.

## 3. Local Storage and Permissions

Financial records, account images, bill pictures, operation history, and relevant settings are primarily stored in the application's private storage on your device. The application is configured to disable Android automatic backup and exclude relevant application data from system cloud backup and device transfer.

The application uses network access and network status permissions for cloud backup, automatic exchange rates, AI features, and local network Web administration. Android WorkManager, which runs scheduled backups, also declares wake-lock, run-at-startup, and foreground-service permissions. Microsoft's sign-in library declares NFC and USB access for hardware security keys, used only if you sign in with one, and may check whether Microsoft Authenticator or Company Portal is installed. Image selection and file import and export use system pickers. The application reads or writes the content you select without requiring access to your entire photo library or all stored files.

To take a bill photo, the application opens the system camera app, which writes the photo to a temporary file the application provides; the application deletes that file once it has read it, and does not request the camera permission itself. The application adds a "Valnook bill" entry to the system share menu to receive text or pictures (up to 4) shared from other apps; you can turn this entry off in Settings › Quick bookkeeping.

To support AI models you run on your own local network, the application allows connections to an http:// service address that you enter. Apart from the AI service address you enter, all other connections, such as OneDrive and exchange rates, use HTTPS only. With an http:// address, requests, including the API key, travel unencrypted; use one only on a trusted network.

Local data protection relies on operating system application isolation and device security. Keep your device and unlock credentials secure.

## 4. Automatic Exchange Rates

Automatic exchange rates are optional, off by default, and run only with your real data; demo mode never goes online. Before the feature is turned on, the application shows the network notice below; you can tick "Don't show again", and that choice is kept on this device only.

Source: rates come from the public Frankfurter exchange rate service (api.frankfurter.dev), which compiles data from central banks and other public sources.

When the app connects: it sends a request on the first launch each day, when the base currency changes, when this feature is turned on, and when you update manually. After a failed request it tries again when you open or return to the app, at least 30 minutes after the last attempt.

What is sent: the base currency code, a fixed list of currencies and the requested dates. The service provider and network intermediaries such as Cloudflare can see your IP address and the time of the request. Requests also carry a header identifying them as Valnook, with no device or account identifier.

What is not sent: balances, accounts, transactions or any other financial records.

Storage: retrieved rates are stored in the database on this device and included in backups. The automatic exchange rate switch and the automatic rate status described in Section 2 are not restored from backups; this device keeps its current settings.

When automatic exchange rates are turned on, the current rates are replaced with the retrieved values. If you turn the feature off, the most recently retrieved rates are kept; rates you entered manually cannot be restored.

Exchange rate data is provided by a third party for reference only. It may differ from the rates actually offered by banks or other institutions and may be delayed or inaccurate. Valnook does not guarantee the accuracy, completeness or timeliness of this data, and neither the data nor any results calculated from it constitute investment, wealth-management or other financial advice.

## 5. AI Features

AI features are optional, off by default, and available only with your real data, not in demo mode. You enter the service address, model, and API key yourself, and the features can be used only after the connection and tool-calling checks pass; the application shows the notice below when they are first turned on.

Provider: AI features are provided by the service at the address you enter. Valnook provides no AI service, and the developer neither handles nor keeps what passes between you and that service. How the service keeps and uses the data it receives is governed by its own policy, which Valnook cannot control.

When the app connects: requests are sent to that address only when you use AI bookkeeping, Ask about bills, recognition of shared content, or the capability checks. The checks send a few very small requests; the picture check sends an image the application draws on the device that contains only random digits.

What is sent: the text and pictures you enter or share, and the earlier text of the same conversation (earlier pictures are not sent again); the current date, time, and base currency; the names of bill categories that are not hidden; the names, currencies, and types of the subaccounts shown on the Accounts page (up to 100), and which are favorites; My rules and both nicknames; with "Send recent bills as hints" on, the notes and categories of the last 20 bills; with "Ask about bills" on and when you ask, the query results: expense and income totals, the date, category, amount, and note of up to 20 bills, and the shares of your assets by account, asset type, and currency (percentages only). The API key is sent in a request header. The provider can also see your IP address and the time of each request.

What is not sent: account balances, credit limits, net worth, asset amounts, Wallet contents, investment holdings and trades, avatars, and device information.

Information in pictures: payment screenshots and other pictures may show the other party's name, the last four digits of a card number, order numbers, and other details, which are sent to the provider with the picture. The application shows a separate notice before the first picture is sent.

API key storage: the API key is encrypted on this device only, with keys managed by Android Keystore and content encrypted with AES-GCM. It is never uploaded to a developer-owned server, never included in local or OneDrive full backups or file exports, and cannot be accessed from Web administration. After uninstalling, clearing the app's storage, clearing all data, or moving to a new device, it must be entered again.

Conversations: conversations and usage records are kept on this device only and are not included in full backups. Conversations are kept for 30 days; usage records hold only each request's time and token counts and are kept for about 13 months. Avatars are kept on this device only and shown only in the application.

Bills need your confirmation: what AI produces from a conversation are bill drafts, which become bills only after you confirm them; nothing is recorded automatically.

Costs and recommendations: charges for using AI features are settled between you and the provider. We recommend setting a usage or spending limit for the API key with the provider and granting it only the permissions needed; if you suspect the key has leaked, revoke or replace it with the provider at once. If you do not want data to leave your device or network, point the service address to a model you run locally.

AI-generated content may contain errors, is for reference only, does not represent Valnook's position, and is not investment, financial, or other advice. **To the fullest extent permitted by applicable law, the developer is not liable for the provider's availability, charges, or handling of data, or for loss arising from misuse of the API key.** See Section 12.

## 6. OneDrive Cloud Backup

OneDrive is optional. When connecting, you sign in through Microsoft's authorization flow and grant access to basic account information and the application's dedicated OneDrive folder. Microsoft's sign-in library (MSAL) handles sign-in and keeps the access credentials; Valnook never sees your Microsoft password.

You may create backups manually or separately enable automatic backup. Automatic backups are scheduled at the configured interval, may run in the background, and may use mobile data. Actual execution depends on network availability and system scheduling. Disabling automatic backup or disconnecting does not delete files already uploaded.

Full backups include financial records (including bills and bill categories), account groups, account images, the Wallet content described in Section 2, AI settings (without the API key), relevant settings, and operation history, which may contain records that have been modified or marked as deleted. Backup information includes creation time, application version, file size, and integrity verification information. Cloud authorization credentials, card-back details, the AI API key, and conversations are not included in full backups.

Bill pictures are not part of the backup file. They are uploaded separately to a bill_images folder inside the application's dedicated folder, shared by all backups: each picture is named by a hash of its content and uploaded only once; each backup adds a list of the pictures it uses; and the folder holds a notice file. After a restore, the pictures stay in that folder and bills show them as In OneDrive; you can fetch them one at a time or all at once from the backup screen. Do not change that folder by hand, or pictures may not be restorable.

**Backup files themselves are not encrypted.** OneDrive transfers use HTTPS, which does not mean that the backup files are end-to-end encrypted.

The application keeps the list of cloud backups (file names and identifiers, creation times, and similar details) and the upload status of bill pictures on this device, to show backups, rotate them, and avoid uploading a picture twice. The backup list is cleared when you disconnect or change the OneDrive connection.

After a successful backup, the application attempts to retain the five most recent verified backups it manages and remove older corresponding backups. A copy that could not be verified after upload is kept for three days and then removed at the next backup rotation. Bill pictures no longer used by any retained backup or existing bill are removed once they have been uploaded for three days. More content may remain if cleanup fails or other files exist, and empty folders are left in place. Whether removed files go to a recycle bin, and for how long they are kept there, is managed by Microsoft.

Microsoft processes information needed to provide its cloud services under its terms and [Privacy Statement](https://privacy.microsoft.com/privacystatement). Storage and processing locations depend on Microsoft services and your account settings and may be outside your region. You can manage or revoke authorization through your Microsoft account, which the backup screen links to, and manage existing backups in OneDrive.

## 7. Local Backup, Import, and Export

You can save full backups, Excel workbooks, or a copy of a OneDrive backup to a destination selected through the system file picker, or select a backup to restore. Destinations may be provided by device storage or third-party document or cloud storage services. If you select a third-party service, that service handles the file under its own rules.

Full backups saved locally do not include bill pictures. After restoring from a local backup, pictures not on this device are shown as missing; with OneDrive connected, they can be fetched from the cloud.

Full backup and Excel export files do not provide file encryption. Full backups may contain operation history, and Excel files may contain financial and historical information within the export scope, including a Bills sheet (with notes, counterparties, refund and reimbursement states, and the number of pictures, but not the pictures themselves). Choose storage destinations and recipients carefully.

Restoring a backup overwrites the corresponding existing data. Clearing the app's storage or uninstalling does not also delete separately saved backups, exports, or copies of those files.

## 8. Local Network Web Administration

After you enable Web administration and connect using a QR code or pairing code, the paired browser can view and edit the financial data available through this feature on the same local network. Bills and income and expense statistics can only be viewed in the browser, not added or changed; bill pictures are not transferred to the browser, which shows only how many there are. Data passes between your phone and browser without being relayed through a developer-owned server.

**Web administration uses HTTP and WebSocket without transport encryption.** Pairing authentication is not encryption. Use this feature only on trusted local networks and devices, and protect your pairing information.

The web interface uses session cookies and browser session storage for authentication, and local storage for theme preferences. The live connection is opened with a one-time ticket valid for 30 seconds, so session credentials never appear in web addresses. These mechanisms support functionality and are not used for advertising tracking. You can remove related site data through your browser.

The connection ends after 5 minutes of inactivity in the web interface, when the phone application goes into the background or the phone is locked, or when you end the session. Ending a session does not retract data already copied, captured in screenshots, or otherwise saved from the browser.

## 9. Retention, Deletion, and Your Choices

Local business records are generally retained until you delete records, clear data, or clear application storage. Operation history may be retained longer than the corresponding visible records. Retention of AI conversations and usage records is described in Section 5.

| Action | Effect and limits |
| --- | --- |
| View, edit, or delete records | You can manage records within the application. Deleting an individual record does not guarantee removal of related contents from operation history. |
| In-app Clear data | Removes all accounts and subaccounts, bills and bill categories (reset to the preset categories), bill pictures on this device, deposits, instruments, holdings, trades and dividends, price history, asset classes (reset to Uncategorized), account groups (reset to the default group), account images, Wallet cards, the card face library, private card-back details, AI settings (reset to defaults), the API key, check results, avatars and conversations, existing operation history, and the base currency, exchange rates, and exchange rate history. It also turns off automatic exchange rates, shows the network notice again, and clears the first-run setup draft. Language, theme, gain/loss colors, navigation bar settings, and the OneDrive connection remain, and the clearing event is recorded. It neither disconnects OneDrive nor deletes bill pictures already uploaded: if automatic backup is on, later backups contain the cleared data, and once five of them exist, older backups are removed under the rotation rules, and pictures no longer used are removed with them. |
| Clear bill pictures | In Settings › Bill pictures, you can clear only pictures on this device that are already backed up to OneDrive (they can be fetched again), or also delete them from OneDrive, in which case the cloud copies are deleted at the next backup and older backups lose these pictures too. With automatic clearing on, pictures backed up to OneDrive are removed from this device once they have been kept or fetched for longer than the period you choose (1, 3, 6, or 12 months); pictures in OneDrive are not deleted. |
| Turn off AI features or delete the API key | Stops further requests; deleting the key removes the key stored on this device. Content already sent to the provider is handled under the provider's own policy. |
| Clear conversations | Clears all conversations and usage records in the AI settings. Unconfirmed drafts are deleted with them; saved bills are not affected. |
| Turn off the share entry | After you turn it off in Settings › Quick bookkeeping, "Valnook bill" no longer appears in the system share menu. |
| Disable automatic backup or disconnect OneDrive | Stops subsequent automatic backups or disconnects this device's cloud connection. It does not delete uploaded files or revoke authorization in your Microsoft account. |
| Revoke Microsoft authorization | Revokes subsequent cloud access. Existing backups must still be managed in OneDrive. |
| Turn off automatic exchange rates | Stops further rate requests. Rates and rate history already retrieved remain on this device. |
| Clear the app's storage or uninstall | Removes the corresponding application data on the device. External files, cloud backups, and other copies are not deleted along with it. |

Exported files remain until you or the storage service delete them. Cloud backups are also subject to the rotation rules and cloud service retention mechanisms described above. The developer does not hold your local financial database or cloud backups and cannot access, restore, or delete them on your behalf. Privacy questions can be sent to the contact email in this policy.

## 10. Third-Party Platforms and Voluntary Contact

When you visit or download content from GitHub, GitHub processes website access, account, and related information under its [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). When you use Microsoft sign-in, OneDrive, or third-party services available through system pickers, those providers process authentication, network connection, service operation, and submitted information under their own rules. When automatic exchange rates are on, the Frankfurter rate service and its network providers such as Cloudflare process the IP address, request time, and the request contents described in Section 4 under their own rules. Frankfurter describes its privacy practices in its [FAQ](https://frankfurter.dev/#faq), and Cloudflare's processing is described in its [Privacy Policy](https://www.cloudflare.com/privacypolicy/). When you use AI features, the AI service provider you choose processes the request contents, your IP address, and the account information tied to the API key under its own rules.

If you voluntarily contact the developer by email or GitHub, the developer receives the contact details, message, and attachments you submit and uses and retains them only to handle your request and necessary follow-up communication. You may contact the developer to request deletion of related correspondence, except where retention is required by applicable law. Public GitHub issues and comments can be viewed by others. Do not submit financial backups, passwords, authorization credentials, API keys, or other sensitive content.

## 11. Minors

Minors should use the software and third-party services under a guardian's guidance and in accordance with applicable local law. Do not submit unnecessary personal information about minors through public feedback or email.

## 12. Open-Source License and Disclaimer

Valnook is provided free of charge as open-source software under the MIT License. The software is provided "as is." To the fullest extent permitted by applicable law, the authors and copyright holders make no express or implied warranties, including merchantability, fitness for a particular purpose, non-infringement, or the accuracy, completeness, availability, or security of data or calculation results.

**Valnook provides recording, calculation, and display tools only. It does not constitute investment, personal finance, financial management, tax, legal, or other professional advice.** Balances, valuations, interest, returns, exchange rates, statistics, and AI-generated content are for your own verification and reference, not a promise of returns or a basis for trading. You should independently verify information and make your own decisions.

You are responsible for safeguarding your device, account credentials, API keys, backups, and exported content, and for choosing trusted networks, devices, and services. To the fullest extent permitted by applicable law, the authors and copyright holders are not liable for data disclosure, loss, corruption, unauthorized access, financial loss, or other damage arising from the use of or inability to use the software, including damage related to third-party services (including AI service providers), network conditions, device security, or user actions.

This policy does not exclude or limit liability that cannot be excluded or limited under applicable law, or remove any rights you cannot legally waive. The MIT License in the repository governs the software's licensing terms.

## 13. Policy Version and Contact

The update date at the beginning of this document identifies this policy's version. Its public text is available in the documentation directory of the official GitHub repository. The Chinese and English versions are intended to convey the same content.

If the privacy policy displayed in the app differs from the privacy policy published in the official GitHub repository, the policy published in the official GitHub repository takes precedence.

For privacy questions or requests, contact **luoishere** at [xlll1314@outlook.com](mailto:xlll1314@outlook.com).
