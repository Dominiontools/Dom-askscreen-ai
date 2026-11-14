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

The in-app dashboard displays registered users, join/last-sign-in dates, per-feature usage, subscription status, and available payment records. Payment records will populate after the later Paystack integration writes verified transactions to `askScreenPayments`. The dashboard is read-only for now; account suspension, manual subscription changes, and refunds are not enabled. The Android app calls `/.netlify/functions/admin-dashboard` using the backend URL configured in Settings. Recent customer support requests are also listed for the administrator.

## 7. In-app customer support

Customers can contact support using the WhatsApp, email, and phone shortcuts in the Android app. Signed-in customers with verified email addresses can also submit private support requests from the Support Centre. Requests are stored in the Firestore `askScreenSupportTickets` collection and are scoped to the submitting Firebase UID. Customers can view only their own requests and replies. The Admin Centre displays the latest support requests for review. The ticket reply workflow is not enabled yet; customers can still receive assistance through the published WhatsApp, email, and phone channels.
