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

## Initialize Firebase

After the generated options file exists, initialize Firebase before constructing `AuthRepository`:

```dart
await Firebase.initializeApp(
  options: DefaultFirebaseOptions.currentPlatform,
);
```

Use the same Firebase Authentication project and Email/Password provider as the existing Android app. The shared app must acquire Firebase ID tokens from the signed-in user and pass them to the existing Netlify API. It must not access protected Firestore account records directly or implement its own subscription decisions.

## Important

- `firebase_options.dart` contains client configuration, not Firebase Admin credentials. Never put `FIREBASE_SERVICE_ACCOUNT_JSON`, AI provider keys or payment secrets in the Flutter app.
- Keep Firebase Authentication as the identity provider. Existing accounts should sign in with their existing credentials.
- Keep usage, free-trial eligibility, subscription entitlements and admin authorization enforced by the Netlify backend.
- Windows and iOS registration must be completed before those platform builds can be run against the real Firebase project.
- Do not merge or deploy the Flutter application as a replacement until Android, iOS and Windows builds and real-device authentication/API tests pass.