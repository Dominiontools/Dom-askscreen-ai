# DOM AskScreen AI — shared Flutter application

This directory is the beginning of one shared Flutter application for Android, iOS and Windows. It is intentionally isolated from the existing Kotlin Android application while migration proceeds.

## Non-negotiable migration rules

- Keep the current Kotlin Android app buildable until the Flutter replacement passes feature-parity and device testing.
- Keep the existing Netlify Functions backend and Firebase project. Do not create a second user database or duplicate subscription authority.
- Never place AI provider keys, Firebase Admin credentials, signing keys, or other server secrets in Flutter.
- Keep subscription, payment approval, trial eligibility, usage limits, and administrator authorization enforced by the existing backend.
- Do not remove or rename existing Android settings or stored preferences during migration.
- Implement platform-dependent behavior behind explicit Android, iOS and Windows adapters.
- Do not merge, publish, deploy, or replace the production Android app without explicit approval.

## Target shared features

1. DOM AskScreen AI chat and AI answer experience, using the existing backend endpoint.
2. Firebase Authentication and the existing account identity.
3. Existing backend-managed usage limits, trial rules, and subscriptions.
4. Work Centre catalogue and each workspace as it is actually implemented.
5. Admin Centre, with server-side administrator authorization unchanged.
6. Chat history and user preferences, with a safe migration path for local Android data.
7. Image, PDF, audio, video and text attachment workflows supported by the backend.
8. Voice input, transcription and text-to-speech, using platform adapters where needed.
9. Privacy settings, including separate Classroom Privacy and Invisible Presentation settings.
10. Account, support, announcements, update information and release metadata.

## Platform capability adapters

### Android

Retain native Kotlin integrations where Flutter plugins are insufficient: MediaProjection, overlay windows, notification/service lifecycle, Android speech recognition, accessibility (only where permitted and explicitly enabled), and Android-specific secure-window behavior.

### iOS

Implement only capabilities permitted by Apple's APIs. Screen recording/mirroring detection and sensitive-content redaction are not equivalent to Android's window protection. iOS does not provide a general Android-style overlay bubble over other apps or unrestricted cross-app screen capture.

### Windows

Use the shared Flutter UI with Windows desktop integrations for window management, optional always-on-top assistant windows, tray/lifecycle behavior, voice and file access, and capture/privacy behavior where Windows APIs support it. Preserve the existing Windows privacy behavior only after verifying the actual native implementation and supported capture paths.

The shared UI must clearly communicate platform limitations instead of showing controls that cannot work on that device.

## Existing backend contract now mapped

- The AI function is the configured HTTPS URL ending in `/.netlify/functions/ask-screen`.
- AI requests use `POST`, a Firebase ID token in `Authorization: Bearer <token>`, and JSON fields `prompt`, `model`, `queryType`, `history`, `images`, `files`, and optional `audio`.
- Successful AI responses contain `success: true` and an `answer` string. Backend error codes such as `AUTH_REQUIRED`, `SUBSCRIPTION_REQUIRED`, `RATE_LIMITED`, `FEATURE_DISABLED` and `UPSTREAM_UNAVAILABLE` are mapped to typed client errors.
- Account status is read from the sibling `account-status` function using authenticated `GET`; subscription and usage decisions remain server-authoritative.
- `lib/network/ask_screen_api.dart` implements this transport contract behind an injectable transport so it can be tested without production credentials.
- Firebase sign-in/token acquisition is not yet wired to the Flutter UI. The caller must supply a fresh Firebase ID token; never persist or hardcode one.

## Suggested structure as migration grows

- lib/app/: app root, theme and navigation
- lib/core/config/: environment and endpoint configuration
- lib/network/: authenticated API client and safe error mapping
- lib/core/auth/: Firebase authentication integration
- lib/features/chat/: AI conversation and response UI
- lib/features/work_centre/: catalogue and implemented workspaces
- lib/features/admin/: admin UI backed by existing protected endpoints
- lib/features/history/: conversation history
- lib/features/settings/: privacy and preferences
- lib/platform/: platform capability interfaces
- android/: native Kotlin platform implementations
- ios/: native Swift platform implementations
- windows/: native Windows desktop integrations

## Current status

The platform capability map and shared API client are in place with unit tests authored. Automated Flutter validation is configured in GitHub Actions but has not yet been confirmed passing. The Flutter shell is not yet connected to Firebase Authentication, so it is not a functional customer app and must not be distributed.

## Validation gates

Before replacing the Android app or preparing iOS/Windows releases:

- Pass Flutter formatting, analysis and unit tests.
- Build and test Flutter on Android, iOS and Windows.
- Verify Firebase sign-in against the existing project.
- Verify AI requests use the existing backend and preserve authenticated identity.
- Verify trial/subscription/usage rules against backend responses.
- Verify history migration and attachment handling.
- Test every native capability on real Android, iOS and Windows devices.
- Complete privacy, accessibility, security and store compliance reviews.
- Keep the production Android package identity and signing/update path compatible for Android upgrades.