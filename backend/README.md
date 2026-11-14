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

Keep both values private. Never put either secret in the Android app, GitHub source files, or a message. Do not commit a service-account JSON file.

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
5. Test the AI features after the backend has deployed with both required environment variables.

All AI requests on this upgrade branch require a signed-in Firebase account and go through the backend. The backend grants each newly registered account two lifetime free uses per feature (screen reading, AI spoken answers, and transcription). Each successful feature request consumes one use. Allowances never reset monthly or when the app is reopened. After a feature's two uses are consumed, an active subscription is required for that feature. A paid subscription is recognized only when a verified active subscription record with a valid future expiry exists in Firestore.

## 5. Paystack — later phase

Paystack is intentionally not required for this deployment. When the Paystack account is approved, we will add server-side checkout initialization, webhook signature verification, and payment verification. Only verified payments will activate subscriptions. Paystack secret keys will be stored in Netlify environment variables, never in the Android app.

Until that integration is complete, do not manually mark customers as paid.
