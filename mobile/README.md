# DOM AskScreen AI — shared Flutter mobile app

This directory is the beginning of the shared Android/iOS mobile application. It is intentionally isolated from the existing Kotlin Android application while migration proceeds.

## Non-negotiable migration rules

- Keep the current Kotlin Android app buildable until the Flutter replacement passes feature-parity and device testing.
- Keep the existing Netlify Functions backend and Firebase project. Do not create a second user database or duplicate subscription authority.
- Never place AI provider keys, Firebase Admin credentials, signing keys, or other server secrets in Flutter.
- Keep subscription, payment approval, trial eligibility, usage limits, and administrator authorization enforced by the existing backend.
- Do not remove or rename existing Android settings or stored preferences during migration.
- Implement platform-dependent behavior behind explicit Android and iOS adapters.
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

The shared UI must clearly communicate platform limitations instead of showing controls that cannot work on that device.

## Suggested structure as migration grows

- lib/app/: app root, theme and navigation
- lib/core/config/: environment and endpoint configuration
- lib/core/network/: authenticated API client and safe error mapping
- lib/core/auth/: Firebase authentication integration
- lib/features/chat/: AI conversation and response UI
- lib/features/work_centre/: catalogue and implemented workspaces
- lib/features/admin/: admin UI backed by existing protected endpoints
- lib/features/history/: conversation history
- lib/features/settings/: privacy and preferences
- lib/platform/: platform capability interfaces
- android/: native Kotlin platform implementations
- ios/: native Swift platform implementations

## Current status

The initial Flutter shell is only a foundation. It is not yet connected to Firebase, the AI backend, subscriptions, Work Centre or Admin Centre. Do not distribute it as a functional app.

## Validation gates

Before replacing the Android app or preparing an iOS release:

- Build and test Flutter on Android and iOS.
- Verify Firebase sign-in against the existing project.
- Verify AI requests use the existing backend and preserve authenticated identity.
- Verify trial/subscription/usage rules against backend responses.
- Verify history migration and attachment handling.
- Test every native capability on real Android and iOS devices.
- Complete privacy, accessibility, security and store compliance reviews.
- Keep the production Android package identity and signing/update path compatible for Android upgrades.
