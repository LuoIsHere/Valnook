# Valnook Privacy Policy and Disclaimer

[简体中文](PRIVACY_POLICY_CN.md)

- Application: Valnook
- Developer: luoishere
- Contact: [xlll1314@outlook.com](mailto:xlll1314@outlook.com)
- Effective date: October 7, 2026
- Last updated: October 8, 2026
- Distribution: [Official GitHub repository](https://github.com/LuoIsHere/Valnook)

## 1. Scope

Valnook is a free, open-source tool for personal financial records and asset management, provided under the [MIT License](../LICENSE) and currently distributed only through the GitHub repository above. This policy applies to the Valnook application released by the developer and its local network web management feature. Providers of modified or redistributed versions are responsible for their versions' data practices.

Valnook primarily stores data locally. The developer does not operate a server to receive or store application user data, and the application does not upload your financial records, images, or backups to a developer-owned server. When you use cloud backup, file export, or web management, relevant data is transferred to your authorized service, chosen destination, or paired browser according to your actions or settings.

## 2. Information Processed and Its Purposes

| Category | Content and purpose |
| --- | --- |
| Financial records | Account names, notes, balances, currencies, credit limits and billing rules, deposits, investment instruments, holdings, transactions, prices, and exchange rates that you enter or import, used for recording, calculations, statistics, and display. |
| Account images | Images you select through the system photo picker and crop for account icons, also included in full backups. |
| Preferences | Language, theme, display, ordering, and other settings used to retain your preferences. |
| Operation history | Contents before and after changes, operation times, and related information used for record consistency, historical review, and backup restoration. Some deleted records may remain in this history. |
| Cloud connection information | Microsoft account identifiers, display names, email addresses or account display information, authorization credentials, and backup status processed when connecting OneDrive, used for authentication and backup management. |
| Web session information | Local network connection addresses, browser identification, pairing and session credentials, and connection and activity times, used for authentication, session management, and access limits. |

Core financial management does not require registering a Valnook account or connecting a cloud service. The application does not use financial records for advertising, marketing profiles, or sale.

Wallet card faces are optional images selected by you through the system photo picker. Static JPEG, PNG and WebP images are cropped, rotated, compressed and processed locally; original location metadata is not retained. Valnook does not perform OCR or collect card numbers, expiry dates or CVV. Use blank or redacted card faces. Cards may link to an existing subaccount without creating a new account or duplicating financial records. Card images, links and order are included in complete local and cloud backups, including OneDrive uploads when enabled. Wallet images are not exposed through web management. Deleting a card does not delete its linked account; clearing all data also removes Wallet data.

## 3. Local Storage and Permissions

Financial records, account images, operation history, and relevant settings are primarily stored in the application's private storage on your device. The application is configured to disable Android automatic backup and exclude relevant application data from system cloud backup and device transfer.

The application uses network access and network status permissions for cloud backup and local network web management. Image selection and file import and export use system pickers. The application reads or writes the content you select without requiring access to your entire photo library or all stored files.

Local data protection relies on operating system application isolation and device security. Keep your device and unlock credentials secure.

## 4. OneDrive Cloud Backup

OneDrive is optional. When connecting, you sign in through Microsoft's authorization flow and grant access to basic account information and the application's dedicated OneDrive folder. Microsoft's authentication component handles sign-in and authorization credentials for access to your authorized cloud storage.

You may create backups manually or separately enable automatic backup. Automatic backups are scheduled at the configured interval, may run in the background, and may use mobile data. Actual execution depends on network availability and system scheduling. Disabling automatic backup or disconnecting does not delete files already uploaded.

Full backups include financial records, account images, relevant settings, and operation history, which may contain records that have been modified or marked as deleted. Backup information includes creation time, application version, file size, and integrity verification information. Cloud authorization credentials are not included in full backups.

**Backup files themselves are not encrypted.** OneDrive transfers use HTTPS, which does not mean that the backup files are end-to-end encrypted.

After a successful backup, the application attempts to retain the five most recent verified backups it manages and remove older corresponding backups. More content may remain if cleanup fails or other files exist. Microsoft's recycle bin and other retention mechanisms are managed by Microsoft.

Microsoft processes information needed to provide its cloud services under its terms and [Privacy Statement](https://privacy.microsoft.com/privacystatement). Storage and processing locations depend on Microsoft services and your account settings and may be outside your region. You can manage or revoke authorization through your Microsoft account and manage existing backups in OneDrive.

## 5. Local Backup, Import, and Export

You can save full backups or Excel workbooks to a destination selected through the system file picker, or select a backup to restore. Destinations may be provided by device storage or third-party document or cloud storage services. If you select a third-party service, that service handles the file under its own rules.

Full backup and Excel export files do not provide file encryption. Full backups may contain operation history, and Excel files may contain financial and historical information within the export scope. Choose storage destinations and recipients carefully.

Restoring a backup overwrites the corresponding existing data. Clearing application data or uninstalling does not also delete separately saved backups, exports, or copies of those files.

## 6. Local Network Web Management

After you enable web management and connect using a QR code or pairing code, the paired browser can view and edit the financial data available through this feature on the same local network. Data passes between your phone and browser without being relayed through a developer-owned server.

**Web management uses HTTP and WebSocket without transport encryption.** Pairing authentication is not encryption. Use this feature only on trusted local networks and devices, and protect your pairing information.

The web interface uses session cookies and browser session storage for authentication, and local storage for theme preferences. These mechanisms support functionality and are not used for advertising tracking. You can remove related site data through your browser.

The connection ends after five minutes of inactivity in the web interface, when the phone application goes into the background or the phone is locked, or when you end the session. Ending a session does not retract data already copied, captured in screenshots, or otherwise saved from the browser.

## 7. Retention, Deletion, and Your Choices

Local business records are generally retained until you delete records, clear data, or clear application storage. Operation history may be retained longer than the corresponding visible records.

| Action | Effect and limits |
| --- | --- |
| View, edit, or delete records | You can manage records within the application. Deleting an individual record does not guarantee removal of related contents from operation history. |
| In-app clear data function | Removes business records, account images, existing operation history, and related financial settings. Some preferences and cloud connection state remain, and the clearing event is recorded. This does not disconnect cloud services. |
| Disable automatic backup or disconnect OneDrive | Stops subsequent automatic backups or disconnects this device's cloud connection. It does not delete uploaded files or revoke authorization in your Microsoft account. |
| Revoke Microsoft authorization | Revokes subsequent cloud access. Existing backups must still be managed in OneDrive. |
| Clear application storage or uninstall | Removes the corresponding application data on the device. External files, cloud backups, and other copies are not deleted along with it. |

Exported files remain until you or the storage service delete them. Cloud backups are also subject to the rotation rules and cloud service retention mechanisms described above. The developer does not hold your local financial database or cloud backups and cannot access, restore, or delete them on your behalf. Privacy questions can be sent to the contact email in this policy.

## 8. Third-Party Platforms and Voluntary Contact

When you visit or download content from GitHub, GitHub processes website access, account, and related information under its [Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). When you use Microsoft sign-in, OneDrive, or third-party services available through system pickers, those providers process authentication, network connection, service operation, and submitted information under their own rules.

If you voluntarily contact the developer by email or GitHub, the developer receives the contact details, message, and attachments you submit and uses and retains them only to handle your request and necessary follow-up communication. You may contact the developer to request deletion of related correspondence, except where retention is required by applicable law. Public GitHub issues and comments can be viewed by others. Do not submit financial backups, passwords, authorization credentials, or other sensitive content.

## 9. Minors

Minors should use the software and third-party services under a guardian's guidance and in accordance with applicable local law. Do not submit unnecessary personal information about minors through public feedback or email.

## 10. Open-Source License and Disclaimer

Valnook is provided free of charge as open-source software under the MIT License. The software is provided "as is." To the fullest extent permitted by applicable law, the authors and copyright holders make no express or implied warranties, including merchantability, fitness for a particular purpose, non-infringement, or the accuracy, completeness, availability, or security of data or calculation results.

**Valnook provides recording, calculation, and display tools only. It does not constitute investment, personal finance, financial management, tax, legal, or other professional advice.** Balances, valuations, interest, returns, exchange rates, and statistics are for your own verification and reference, not a promise of returns or a basis for trading. You should independently verify information and make your own decisions.

You are responsible for safeguarding your device, account credentials, backups, and exported content, and for choosing trusted networks, devices, and services. To the fullest extent permitted by applicable law, the authors and copyright holders are not liable for data disclosure, loss, corruption, unauthorized access, financial loss, or other damage arising from the use of or inability to use the software, including damage related to third-party services, network conditions, device security, or user actions.

This policy does not exclude or limit liability that cannot be excluded or limited under applicable law, or remove any rights you cannot legally waive. The MIT License in the repository governs the software's licensing terms.

## 11. Policy Version and Contact

The update date at the beginning of this document identifies this policy's version. Its public text is available in the documentation directory of the official GitHub repository. The Chinese and English versions are intended to convey the same content.

For privacy questions or requests, contact **luoishere** at [xlll1314@outlook.com](mailto:xlll1314@outlook.com).
