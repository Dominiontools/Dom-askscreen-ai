# AskScreen AI — Netlify Backend

This backend securely handles screen-reading, spoken-answer, and transcription requests. The Android app sends a Firebase Authentication ID token; the backend verifies it and enforces the free-use allowance in Firestore.

## 1. Netlify deployment

1. Open Netlify and import this GitHub repository.
2. Set the **Base directory** to `backend`.
3. Netlify will use `backend/netlify.toml` and install the dependencies from `backend/package.json`. The static publish directory is restricted to `backend/public`; backend source files and environment templates are not published as the site root.
4. The function URL will be:
   `https://YOUR-SITE.netlify.app/.netlify/functions/ask-screen`

## 2. Required Netlify environment variables

Open **Site configuration → Environment variables** and add:

- `GEMINI_API_KEY` — your server-side Gemini API key.
- `FIREBASE_SERVICE_ACCOUNT_JSON` — the complete Firebase service-account JSON for the same Firebase project used by the Android app. Paste the full JSON as the variable value.
- `FREE_TRIAL_START_AT` — the UTC ISO-8601 launch timestamp from which newly created Firebase accounts become eligible for the two-use-per-feature free trial (example format: `2026-11-15T00:00:00.000Z`). Choose the actual public launch time and keep it fixed. If this variable is missing or invalid, free trials are disabled rather than granted to older accounts.

Keep both values private. Never put either secret in the Android app, GitHub source files, or a message. Do not commit a service-account JSON file.

- `ADMIN_EMAILS` — comma-separated list of verified Firebase administrator email addresses. Required to enable the in-app Admin Centre.

After adding or changing variables, trigger a new Netlify deploy.

## 3. Firebase console setup

1. Confirm the Android app's Firebase project is the same project as the service account.
2. In Firebase Authentication, enable **Email/Password** sign-in.
3. Create or confirm the Firestore database in that project. The backend stores per-account usage in the `askScreenAccounts` collection.
4. The repository now pins the Firebase CLI default project to the same project ID used by `app/google-services.json`. From the repository root, run `cd backend` and then `npx firebase-tools deploy --only firestore:rules` (or install Firebase CLI and run `firebase deploy --only firestore:rules`). Confirm the target project shown by the CLI before approving deployment. These rules deny direct app-client reads and writes; the trusted Netlify backend uses the Firebase Admin SDK to access records. Do not replace them with public read/write rules.
5. Generate a service-account key from Firebase project settings → Service accounts, and store its complete JSON only in Netlify as `FIREBASE_SERVICE_ACCOUNT_JSON`.

The Firebase Admin service account must have permission to verify Firebase ID tokens and read/write Firestore. Treat its private key as a production secret.

## 4. Configure the Android app

1. Open AskScreen AI → **Settings**.
2. Enter the Netlify function URL shown above.
3. Save the URL.
4. Open **Account** and create an account or sign in.
5. Open **Account** to see your screen, spoken-answer, and transcription usage, free-trial eligibility, and subscription status. The account screen reads this information from the authenticated `/.netlify/functions/account-status` endpoint.
6. Test the AI features after the backend has deployed with the required backend environment variables configured.

All AI requests on this upgrade branch require a signed-in Firebase account and go through the backend. The backend grants a configurable lifetime free allowance per feature (screen reading, AI spoken answers, and transcription) only to Firebase accounts whose trusted Firebase Authentication creation time is on or after `FREE_TRIAL_START_AT`. The default is two uses per feature; an authorized administrator can change the limit from 0 to 10 in App & Feature Settings. Accounts created before the trial start timestamp do not receive a trial, even if their Firestore usage record is missing or recreated. Each successful feature request consumes one use. Allowances never reset monthly or when the app is reopened. When a feature's allowance is exhausted, an active subscription is required for that feature. A paid subscription is recognized only when a verified active subscription record with a valid future expiry exists in Firestore.

## 5. Customer account deletion

The Android Account screen provides **Delete my account and data**. Customers must re-enter their email/password credentials; the backend then requires a Firebase ID token whose `auth_time` is no more than five minutes old. The `/.netlify/functions/delete-account` endpoint deletes the Firebase Authentication user and the associated Firestore account document (including nested subcollections), support tickets, activity logs, payment records and user-linked admin audit records. It never deletes global app configuration. The action is irreversible. Local chat history on the device and data already held by third-party AI providers are outside this server-side deletion operation and must be handled through device controls/provider retention policies. Keep the privacy policy aligned with actual retention and applicable law.

## 6. Payments — USDT manual review; Paystack coming soon

The backend provides an initial manual USDT payment workflow for independent Work Centre services. Paystack remains disabled and is not a payment option until the account is approved and the full server-side integration is implemented.

### Supported USDT networks

- **BEP20 (BNB Smart Chain):** `0xc0C830d8165E9ff2Da899397128dB9247d64154F`
- **TRC20 (TRON):** `TSmksTL3haP4iSQC49j5mMpF9xw6feoXH`

The public payment-instructions endpoint is `/.netlify/functions/crypto-payments` (GET). Optional Netlify variables `USDT_BEP20_ADDRESS` and `USDT_TRC20_ADDRESS` can override the public receiving addresses. Never put wallet seed phrases or private keys in the app, Netlify, or GitHub.

### Customer submission

POST JSON to `/.netlify/functions/crypto-payments` with `email`, `productId`, `network` (`BEP20` or `TRC20`), `transactionHash`, and `amountUSDT`. The API validates the email, independent product ID, network-specific transaction-hash format and amount, and prevents a transaction hash from being submitted twice on the same network. A submission is only a pending review record; it does not unlock any product.

Customers must send USDT on the exact selected network. Sending on the wrong network can result in permanent loss. The submitted amount is not automatically converted from the USD catalogue price; the administrator must compare the received amount with the intended product price and any agreed exchange rate before approval. Screenshots are supporting information only and are not proof of payment.

### Admin review and entitlement

POST JSON to `/.netlify/functions/admin-crypto-payments` with `paymentId`, `action` (`approve` or `reject`), and optional `note`. The endpoint requires a signed-in, email-verified administrator listed in `ADMIN_EMAILS`. Only pending USDT records can be reviewed. Approval/rejection uses a Firestore transaction so two admins cannot approve the same payment concurrently. Approval grants only the selected independent Work Centre product for 30 days and writes an audit record; rejection grants no access. An approved payment awaiting account creation is claimed atomically after the customer registers and verifies the same email, preventing a partially claimed payment from being lost.

If payment is approved before the customer registers, the payment remains available to claim. After the customer creates an account and verifies the same email address, the authenticated `account-status` endpoint claims the approved entitlement and attaches it to that account. The entitlement is never granted merely because a customer submits payment details.

The admin dashboard API includes the payment provider, service, network, destination, amount, transaction hash, status and review metadata. The Android app now hides the Email Management launch action unless the signed-in account has a verified email and an active Email Management entitlement. The Android Admin Centre still needs a dedicated payment-review screen wired to this API before the complete in-app review experience is customer-ready. Support requests remain available through the existing Support Centre while a payment is pending.

### Paystack

Paystack is shown as `Coming Soon` and disabled. After the account is approved, implement server-side checkout initialization, webhook signature verification, server-side transaction verification, idempotency and entitlement activation. Keep all Paystack secret keys in Netlify environment variables, never in the Android app. Do not accept payments through Paystack until those controls and end-to-end tests are complete.

Manual access grants remain administrative overrides, not payment records. They must not be represented as customer payments or as DOM AI Core subscriptions.

## 7. In-app administrator centre

The administrator centre is part of the Android app. Open **Account → Open Admin Centre**. It uses the same Firebase Authentication project as customer accounts, but the backend grants access only to verified email addresses listed in the Netlify `ADMIN_EMAILS` environment variable.

Add `ADMIN_EMAILS` as one or more authorized Firebase administrator email addresses separated by commas. The server checks the signed Firebase ID token, verified email status, and allowlist on every dashboard request. Do not create public admin registration and do not rely on hiding the screen as security.

The in-app dashboard displays registered users, join/last-sign-in dates, per-feature usage, subscription status, available payment records, recent customer support requests, recent app activity/errors, and editable app settings. AI request activity records only technical metadata (account, feature, outcome, error code, provider/model, request ID and timestamp); it does not store customer prompts, screenshots, audio, or uploaded file contents. Payment records will populate after the later Paystack integration writes verified transactions to `askScreenPayments`. Administrators can search and filter registered accounts, inspect email verification and account status, enable or disable a customer account, reply to a support ticket, and mark it open, in progress, or resolved. Account enable/disable actions and support-ticket replies/status changes are written to `askScreenAdminAuditLogs` with the administrator identity, affected account or ticket, action, and server timestamp. The in-app dashboard displays the latest 100 records. Recent AI request activity and technical failures are stored separately in `askScreenActivityLogs`; the dashboard displays the latest 100 records. The Android app calls `/.netlify/functions/account-status` to show each signed-in customer only their own usage and subscription status. It calls `/.netlify/functions/admin-dashboard` to load administrator data, `/.netlify/functions/support-admin` to send replies or update ticket status, `/.netlify/functions/admin-users` to enable or disable accounts, and `/.netlify/functions/admin-settings` to read or update operational settings. The settings endpoint allows public GET access only to customer-safe announcement/feature configuration; all changes require an authorized administrator. Each management endpoint verifies the Firebase ID token, verified email, and `ADMIN_EMAILS` allowlist on the server. The App & Feature Settings section lets authorized admins update a customer announcement, switch screen/spoken/transcription features on or off, and set the free-use limit from 0 to 10; the backend enforces feature switches and limits on every request. These are operational settings, not source-code editing. DOM AI Core subscription changes and refunds are not enabled. A separate **Manage Work Centre access** control allows an authorized administrator to grant or revoke an independent specialist product for a selected customer until a specified future expiry date (maximum two years). Every grant/revoke is written to the admin audit log. This is an access override only: it does not create a payment record, prove that money was received, or activate DOM AI Core.

## 8. In-app customer support

Customers can contact support using the WhatsApp, email, and phone shortcuts in the Android app. Signed-in customers with verified email addresses can also submit private support requests from the Support Centre. Requests are stored in the Firestore `askScreenSupportTickets` collection and are scoped to the submitting Firebase UID. Customers can view only their own requests and replies. The Admin Centre displays the latest support requests. Authorized administrators can send a reply and update the ticket status; customers can see the reply and status in their Support Centre ticket history after refreshing. Support ticket documents should be accessed through these authenticated backend functions, not exposed directly to unauthenticated clients. Customers can also receive assistance through the published WhatsApp, email, and phone channels.


## 9. Android APK Update Centre — GitHub Releases

The Admin Centre stores release metadata in Firebase Firestore. APK binaries are hosted as assets in GitHub Releases, so no Cloudflare R2 bucket, Firebase Storage bucket, storage API key, or bank card is needed for APK hosting.

### Publish a release

1. Build a signed APK with package name `com.dominion.askscreenai`, the same release signing key already used for customer installations, and a higher `versionCode`.
2. Open the repository's [GitHub Releases page](https://github.com/Dominiontools/Dom-askscreen-ai/releases) and create a release with a new tag (for example `v1.1`).
3. Attach the signed APK file to the release and publish it. The release asset must have a `.apk` extension.
4. Copy the asset download link. It must begin with `https://github.com/Dominiontools/Dom-askscreen-ai/releases/download/`.
5. In the in-app Admin Centre → App Update Centre, enter the exact version name and version code, paste the APK asset link, optionally enter the APK size in bytes, add release notes, and select **Publish update link**.
6. The backend verifies the Firebase ID token, verified admin email, allowlist, repository-specific GitHub URL, and increasing version code before saving the current release in Firestore.
7. Customer apps check the public release metadata, download the APK from GitHub, check the expected file size when supplied, and open Android's installation approval screen.

### Important safeguards

- Keep the GitHub release asset public. Never upload customer data, credentials, service-account JSON, or private files to a release.
- Keep `FIREBASE_SERVICE_ACCOUNT_JSON` and `ADMIN_EMAILS` configured in Netlify. No GitHub token is stored in the Android app or Netlify for this public-download method.
- For every release, increase `versionCode` and update `versionName` in `app/build.gradle.kts` before building the signed release APK.
- Never uninstall the existing app before updating. A normal in-place update preserves local app data when package name and signing certificate match.
- Android may ask each customer to allow AskScreen AI to install apps from this source and approve the installation. Silent installation is not available to ordinary apps.
- Test the upgrade on a spare phone using the exact APK currently distributed before publishing an update to customers.

## 10. Subscription product pricing

The Admin Centre's **App & feature settings → Subscription & product pricing** section manages the public monthly product catalogue stored in `askScreenAppConfig/public`.

- DOM AI Core defaults to USD $9/month.
- Each specialist AI module has its own monthly price.
- DOM Email Management defaults to USD $20/month and remains an independent product.
- The complete AI suite includes DOM AI Core and all specialist modules.
- The complete business bundle adds the separate Email Management product.
- The default complete-bundle discount is 10%; administrators can change it from 0–30%.
- Prices are stored as integer US cents to avoid floating-point billing errors.
- The public GET response exposes only customer-safe catalogue data and calculated bundle totals.
- Only a verified administrator in `ADMIN_EMAILS` can update prices. Changes are audited through the existing admin settings audit log.

Default catalogue arithmetic:
- Specialist AI modules: $516/month.
- DOM AI Core: $9/month.
- Complete AI suite before discount: $525/month; after 10% discount: $472.50/month.
- Email Management: $20/month.
- Complete business bundle before discount: $545/month; after 10% discount: $490.50/month.

These are catalogue prices, not payment activation. Paystack checkout and verified payment webhooks remain a separate future implementation; do not grant paid access merely because a customer views the catalogue. The manual Work Centre access override described above is separate from payment processing and must never be represented as a completed payment.
