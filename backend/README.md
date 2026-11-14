# AskScreen AI — Netlify Backend

This backend securely handles screen-reading, spoken-answer, and transcription requests. The Android app sends a Firebase Authentication ID token; the backend verifies it and enforces the free-use allowance in Firestore.

## 1. Netlify deployment

1. Open Netlify and import this GitHub repository.
2. Set the **Base directory** to `backend`.
3. Netlify will use `backend/netlify.toml` and install the dependencies from `backend/package.json`.
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
4. Generate a service-account key from Firebase project settings → Service accounts, and store its complete JSON only in Netlify as `FIREBASE_SERVICE_ACCOUNT_JSON`.

The Firebase Admin service account must have permission to verify Firebase ID tokens and read/write Firestore. Treat its private key as a production secret.

## 4. Configure the Android app

1. Open AskScreen AI → **Settings**.
2. Enter the Netlify function URL shown above.
3. Save the URL.
4. Open **Account** and create an account or sign in.
5. Test the AI features after the backend has deployed with the required backend environment variables configured.

All AI requests on this upgrade branch require a signed-in Firebase account and go through the backend. The backend grants two lifetime free uses per feature (screen reading, AI spoken answers, and transcription) only to Firebase accounts whose trusted Firebase Authentication creation time is on or after `FREE_TRIAL_START_AT`. Accounts created before that timestamp do not receive a trial, even if their Firestore usage record is missing or recreated. Each successful feature request consumes one use. Allowances never reset monthly or when the app is reopened. After a feature's two uses are consumed, an active subscription is required for that feature. A paid subscription is recognized only when a verified active subscription record with a valid future expiry exists in Firestore.

## 5. Paystack — later phase

Paystack is intentionally not required for this deployment. When the Paystack account is approved, we will add server-side checkout initialization, webhook signature verification, and payment verification. Only verified payments will activate subscriptions. Paystack secret keys will be stored in Netlify environment variables, never in the Android app.

Until that integration is complete, do not manually mark customers as paid.

## 6. In-app administrator centre

The administrator centre is part of the Android app. Open **Account → Open Admin Centre**. It uses the same Firebase Authentication project as customer accounts, but the backend grants access only to verified email addresses listed in the Netlify `ADMIN_EMAILS` environment variable.

Add `ADMIN_EMAILS` as one or more authorized Firebase administrator email addresses separated by commas. The server checks the signed Firebase ID token, verified email status, and allowlist on every dashboard request. Do not create public admin registration and do not rely on hiding the screen as security.

The in-app dashboard displays registered users, join/last-sign-in dates, per-feature usage, subscription status, available payment records, recent customer support requests, recent app activity/errors, and editable app settings. AI request activity records only technical metadata (account, feature, outcome, error code, provider/model, request ID and timestamp); it does not store customer prompts, screenshots, audio, or uploaded file contents. Payment records will populate after the later Paystack integration writes verified transactions to `askScreenPayments`. Administrators can search and filter registered accounts, inspect email verification and account status, enable or disable a customer account, reply to a support ticket, and mark it open, in progress, or resolved. Account enable/disable actions and support-ticket replies/status changes are written to `askScreenAdminAuditLogs` with the administrator identity, affected account or ticket, action, and server timestamp. The in-app dashboard displays the latest 100 records. Recent AI request activity and technical failures are stored separately in `askScreenActivityLogs`; the dashboard displays the latest 100 records. The Android app calls `/.netlify/functions/admin-dashboard` to load data, `/.netlify/functions/support-admin` to send replies or update ticket status, `/.netlify/functions/admin-users` to enable or disable accounts, and `/.netlify/functions/admin-settings` to read or update operational settings. The settings endpoint allows public GET access only to customer-safe announcement/feature configuration; all changes require an authorized administrator. Each management endpoint verifies the Firebase ID token, verified email, and `ADMIN_EMAILS` allowlist on the server. The App & Feature Settings section lets authorized admins update a customer announcement, switch screen/spoken/transcription features on or off, and set the free-use limit from 0 to 10; the backend enforces feature switches and limits on every request. These are operational settings, not source-code editing. Manual subscription changes and refunds are not enabled.

## 7. In-app customer support

Customers can contact support using the WhatsApp, email, and phone shortcuts in the Android app. Signed-in customers with verified email addresses can also submit private support requests from the Support Centre. Requests are stored in the Firestore `askScreenSupportTickets` collection and are scoped to the submitting Firebase UID. Customers can view only their own requests and replies. The Admin Centre displays the latest support requests. Authorized administrators can send a reply and update the ticket status; customers can see the reply and status in their Support Centre ticket history after refreshing. Support ticket documents should be accessed through these authenticated backend functions, not exposed directly to unauthenticated clients. Customers can also receive assistance through the published WhatsApp, email, and phone channels.


## 8. Self-hosted Android APK Update Centre — Cloudflare R2

The Admin Centre includes an **App Update Centre**. APK files are stored in a private Cloudflare R2 bucket. Netlify creates short-lived, signed upload/download URLs; the APK itself is transferred directly between the admin/customer phone and R2, not through a Netlify Function. Firebase Firestore continues to store release metadata and Firebase Authentication continues to verify admin accounts.

### Set up Cloudflare R2

1. Create or sign in to a Cloudflare account.
2. Open **Storage & databases → R2 Object Storage** and activate R2.
3. Create a **Standard** bucket, for example `dom-ask-ai-releases`. Do not make the bucket public; the app uses temporary signed URLs.
4. Open **R2 → Manage R2 API Tokens** and create an API token restricted to this bucket with **Object Read & Write** permissions. Copy the Access Key ID and Secret Access Key when Cloudflare shows them. Never put either value in the Android app or GitHub.
5. Copy the Cloudflare account ID from the R2 dashboard.

### Add these Netlify environment variables

- `R2_ACCOUNT_ID` — Cloudflare account ID.
- `R2_ACCESS_KEY_ID` — bucket-scoped R2 Access Key ID.
- `R2_SECRET_ACCESS_KEY` — matching R2 Secret Access Key.
- `R2_BUCKET_NAME` — exact bucket name, for example `dom-ask-ai-releases`.

Keep the existing `FIREBASE_SERVICE_ACCOUNT_JSON` and `ADMIN_EMAILS` variables. Firebase is still used for authentication, Firestore release records, and admin audit logs; **APK file storage no longer uses Firebase Storage**. After adding the four R2 variables, trigger a new Netlify deploy.

### R2 cost

Use the **Standard** storage class. Cloudflare's published free monthly allowance currently includes 10 GB-month of storage, 1 million Class A operations, and 10 million Class B operations; Internet egress is free. Usage above the allowance is billed at the published rates. Infrequent Access does not receive the same free allowance, so do not select it for app releases. Check the current [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/) and the account's billing dashboard before publishing. R2 activation uses Cloudflare's subscription/checkout flow; free included usage does not mean unlimited service or guarantee that every account can activate it without completing billing setup.

### Publish a release

- In the Admin Centre, select the release APK, enter the APK's actual version name and version code, add release notes, and choose **Upload and publish update**. The admin app checks the APK package name, actual version, and signing certificate against the app installed on that administrator phone before upload.
- For every release, increase `versionCode` and update `versionName` in `app/build.gradle.kts` before building the signed release APK. The version details entered in the Admin Centre must match the APK exactly.
- Keep the package name `com.dominion.askscreenai` and the exact same release signing key used for the APK already installed by customers. Do not replace or regenerate the keystore. Android rejects updates with a different package name or signing identity.
- Customer apps check the published release, receive a prompt, and download the APK using a short-lived signed R2 URL. The app verifies the file size and available MD5 checksum before opening Android's installation approval screen.
- Ordinary Android apps cannot silently install APKs on personal phones. Customers may need to allow AskScreen AI to install apps from this source in Android settings. Existing customer app data is preserved by a normal signed in-place update; do not uninstall the app first.

Before the first customer release, test an upgrade from the exact currently distributed APK on a spare Android phone. Confirm that R2 upload/download works, the version is higher, the installed app and new APK have the same signing certificate, Android accepts the update, and existing customer sign-in/data remain present. Do not publish an APK as a production update until this test succeeds.
