# DOM Ask AI

**Floating on-screen AI assistance for Android.**

DOM Ask AI is an Android assistant that can read a user-selected screen, answer spoken questions captured through the device microphone, analyse shared files, and keep a floating answer window available over other apps.

> **Release status:** Development build. Automated backend and Android tests have passed on the preceding feature commit. A debug APK is available for testing; a customer-ready signed release still requires the existing production signing key, Firebase/Netlify configuration, and real-device verification.

## What the app includes

### Core Android experience
- Floating assistant bubble and resizable answer window.
- Screen capture initiated by the user through Android's MediaProjection permission flow.
- Ask questions about the current screen and receive structured answers.
- Continuous microphone speech-recognition sessions that collect phrases and analyse them together when the user stops the session.
- English (Nigeria), Yoruba (Nigeria), and device-default speech-recognition language choices, subject to the installed Android speech service.
- Optional text-to-speech playback and adjustable speech rate.
- Local chat history with a setting to disable saving.
- Share/import support for text, images, PDF, audio, and video files where the receiving app and backend support the content.
- Persistent notification controls for the assistant and screen monitoring.
- Optional accessibility-based practice interaction, disabled by default and limited to controls exposed by the target app.

### Accounts and usage
- Firebase email/password account registration and sign-in.
- Verified account identity required for authenticated AI requests.
- Backend-managed feature usage and subscription status.
- Configurable lifetime free-use allowance for eligible accounts created on/after the configured launch timestamp.
- Separate usage counters for screen answers, spoken answers, and transcription.
- DOM AI Core subscription status and independent Work Centre product access status.

### Admin Centre
- Verified Firebase administrator allowlist checked by the backend on every protected action.
- User and account monitoring, feature usage, subscription records, recent activity and technical errors.
- Customer account enable/disable controls.
- Customer support ticket review, replies and status changes.
- App announcement, feature switches, free-use limit and product pricing configuration.
- Manual grant/revoke of independent Work Centre access with expiry and audit log.
- APK update metadata publishing with GitHub Releases URL validation and required SHA-256 checksum.

### Work Centre catalogue
The app currently displays independently priced specialist service listings and access status. The catalogue is **not** evidence that each specialist service has been built as an executable tool inside this Android app. DOM Email Management opens its separate web application.

## Important technical boundaries

- **Audio:** listening uses Android speech recognition and microphone input. It is not direct capture of another app's internal/system playback. Headphones, protected audio and Android restrictions can prevent acoustic capture.
- **Screen monitoring:** screen sampling detects visual changes and may miss subtle changes or react to animation. It is not guaranteed OCR/event detection.
- **Accessibility:** automatic selection/submit is optional and best-effort. It requires the user to enable Android Accessibility access and only works when the target app exposes readable options and clearly labelled controls. Use only where permitted.
- **AI answers:** AI output can be wrong. Verify important information and keep control of submissions and decisions.
- **APK installation:** Android requires user approval. The app cannot silently install updates on ordinary personal devices.

## Architecture

- Android: Kotlin, Jetpack Compose, Firebase Authentication, local Room history, Android MediaProjection, SpeechRecognizer, Text-to-Speech, optional AccessibilityService.
- Backend: Netlify Functions (Node.js) and Firebase Admin SDK.
- Data: Firebase Authentication and Firestore.
- AI: provider keys stay on the backend; the health endpoint reports configuration status without exposing secrets.
- APK distribution: public GitHub Release assets plus Firestore release metadata and client-side SHA-256 verification.

## Build and test

1. Open the repository in Android Studio.
2. Install Android SDK 36 and a compatible JDK/Gradle toolchain.
3. Configure the Firebase project used by the Android app.
4. Run backend tests from `backend/` using `npm test`.
5. Run Android unit/Robolectric tests with `./gradlew :app:testDebugUnitTest`.
6. Build a debug APK with `./gradlew :app:assembleDebug`.
7. Test on physical devices, especially Android 14–16, before release.

GitHub Actions runs backend validation tests, Android unit/Robolectric tests, and a debug APK build. Debug APKs are for testing only.

## Production setup

See [Backend deployment and configuration](backend/README.md).

Required external configuration includes:
- Firebase Authentication Email/Password enabled.
- Firestore database and the restrictive rules in `backend/firestore.rules`.
- Netlify `FIREBASE_SERVICE_ACCOUNT_JSON`, `ADMIN_EMAILS`, `FREE_TRIAL_START_AT`, and the required AI provider key(s).
- Android app's backend URL configured in Settings.
- Existing release keystore configured as GitHub Actions secrets before a signed release can be built.

Never put service-account JSON, AI provider keys, keystore files, passwords, or debug tokens in source control or chat.

## Payments and commercial services

Paystack checkout, verified webhook handling, and automatic paid subscription activation are not implemented yet. Product prices shown in the app are catalogue prices, not proof of payment. Manual Work Centre grants are audited access overrides, not payment transactions.

## Privacy and terms

- [Privacy Policy](docs/PRIVACY_POLICY.md)
- [Terms of Service](docs/TERMS_OF_SERVICE.md)

These documents describe the current development build. Review and adapt them for the actual business entity, jurisdictions, final provider list, retention practices and production support process before public commercial release.

## Release checklist

- [ ] Confirm Firebase project and Android app configuration.
- [ ] Enable Firebase Email/Password sign-in.
- [ ] Deploy Firestore rules.
- [ ] Configure Netlify environment variables and deploy functions.
- [ ] Configure verified admin email allowlist.
- [ ] Confirm AI provider configuration and budget/rate limits.
- [ ] Configure the existing production keystore secrets in GitHub Actions.
- [ ] Verify the signed APK certificate matches the currently distributed app.
- [ ] Test registration, login, password reset, email verification, account status and sign-out on device.
- [ ] Test screen capture, microphone permissions, speech recognition, notification controls, accessibility opt-in and background restrictions on Android 14–16.
- [ ] Test support requests, admin authorization, audit logs and account controls.
- [ ] Test update download, SHA-256 verification and in-place installation from the existing signed APK.
- [ ] Review privacy policy, terms, app icon, screenshots, support details and pricing.
- [ ] Complete Paystack integration and payment/webhook tests before accepting online payments.
- [ ] Only then approve and publish a customer release.

## Project status

PR #3 (account authentication and usage enforcement) and PR #4 (admin monitoring and product catalogue) are development changes awaiting review. They have not been merged or deployed. Do not distribute the debug artifact as a production update.
