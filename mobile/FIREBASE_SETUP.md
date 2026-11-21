# Firebase setup for the shared Flutter app

## Existing project to reuse

The current Android app is registered in Firebase project `gen-lang-client-0417033738` with Android package ID `com.dominion.askscreenai`. The Flutter Android target must keep that exact application ID and compatible signing/update identity so existing Android users can eventually upgrade rather than install a second app.

Do not create a new Firebase project. Keep the existing user accounts and backend service-account configuration.

## Generate platform configuration

On a computer with Flutter, Android tooling and the FlutterFire CLI installed:

```bash
dart pub global activate flutterfire_cli
cd mobile
flutterfire configure --project=gen-lang-client-0417033738 --platforms=android,ios,windows
```

The configuration command requires an authorized Firebase account. Confirm that Android uses `com.dominion.askscreenai`. Register the iOS bundle identifier in the same Firebase project and complete any Apple Developer setup required for distribution. Windows must also be configured in the same Firebase project.

The FlutterFire CLI generates `lib/firebase_options.dart` and native configuration files. Review the generated files and ensure the app identifiers match the intended platform registrations. Do not commit service-account JSON files, private keys or other server credentials.

## Initialize Firebase and configure the AI endpoint

The current Flutter entry point calls `Firebase.initializeApp()` before creating the authentication repository. Ensure the native Firebase configuration files are present for the platform being built. After FlutterFire generates `lib/firebase_options.dart`, update the bootstrap to pass `DefaultFirebaseOptions.currentPlatform` if the selected platform requires explicit options; do not invent or copy credentials from another Firebase project.

Use the same Firebase Authentication project and Email/Password provider as the existing Android app. The shared app acquires Firebase ID tokens from the signed-in user and passes them to the existing Netlify API. It must not access protected Firestore account records directly or implement its own subscription decisions.

The Flutter app now defaults to the same existing HTTPS backend endpoint already configured in the native Android app: `https://domaskscreenai.netlify.app/.netlify/functions/ask-screen`. You can override it at build time with `--dart-define=ASK_SCREEN_ENDPOINT=...` if the centrally configured endpoint changes. Do not put provider API keys or other secrets in this value.

## Important

- `firebase_options.dart` contains client configuration, not Firebase Admin credentials. Never put `FIREBASE_SERVICE_ACCOUNT_JSON`, AI provider keys or payment secrets in the Flutter app.
- Keep Firebase Authentication as the identity provider. Existing accounts should sign in with their existing credentials.
- Keep usage, free-trial eligibility, subscription entitlements and admin authorization enforced by the Netlify backend.
- Windows and iOS registration must be completed before those platform builds can be run against the real Firebase project.
- Do not merge or deploy the Flutter application as a replacement until Android, iOS and Windows builds and real-device authentication/API tests pass.

## Windows desktop authentication

The Windows client uses Firebase Authentication's HTTPS REST API because the FlutterFire Authentication plugin does not provide the Windows desktop implementation required by this app. It uses the same Firebase project, Email/Password provider and existing user records as Android and iOS.

The Web app registration supplies the Firebase client API key and project configuration. The API key is client configuration, not a service-account secret. The Windows REST adapter currently uses the Web API key for project `gen-lang-client-0417033738`; restrict that key in Google Cloud API restrictions to the Firebase Authentication / Identity Toolkit APIs where supported. Never add a Firebase Admin service-account key to the desktop app.

Windows stores only the Firebase refresh token through `flutter_secure_storage`. The package encrypts stored values and uses Windows Credential Manager to protect its encryption key. Short-lived ID tokens are refreshed when needed and sent only to the existing HTTPS Netlify backend. The backend remains responsible for verifying ID tokens, account status, usage, free-trial eligibility, subscriptions and admin permissions.

Windows authentication supports:
- Existing-account email/password sign-in.
- New-account registration and verification email.
- Resending the verification email and checking verification status.
- Password reset email.
- Secure persisted sessions and sign-out.

For a Windows build, install the C++ ATL component with Visual Studio Build Tools as required by `flutter_secure_storage`. Real-device testing is still required before release.
