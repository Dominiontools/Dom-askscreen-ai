# DOM AskScreen AI — Android, iOS and Windows migration

## Product target

Deliver one DOM AskScreen AI product with a shared Flutter application layer and platform-specific native integrations for Android, iOS and Windows. Users should keep one account, one subscription entitlement and one backend service regardless of device.

This is a staged migration, not a replacement of the working Android application today.

## Platform strategy

| Area | Android | iOS | Windows |
| --- | --- | --- | --- |
| Shared Flutter UI and app logic | Yes | Yes | Yes |
| Existing Firebase identity | Reuse | Reuse | Reuse |
| Existing Netlify AI/backend services | Reuse | Reuse | Reuse |
| Account and subscription authority | Backend | Backend | Backend |
| Floating assistant over other apps | Native Android overlay with permission | Not available as a general Android-style overlay | Native desktop always-on-top window where appropriate |
| Screen capture / privacy | MediaProjection and Android secure-window APIs where supported | Apple-approved capture awareness and app-level privacy handling; no promise of Android-equivalent hiding | Windows native capture/window protection, subject to API and app support |
| Voice and files | Native/plugin adapters | Native/plugin adapters | Desktop/plugin adapters |
| Distribution | APK / Play Store | TestFlight / App Store | Windows installer / MSIX |

## Shared service rules

- Keep Firebase as the identity provider and keep the existing user records.
- Keep Netlify Functions as the trusted server boundary for AI, usage, billing, subscription and admin operations.
- Never ship provider API keys, Firebase Admin credentials or other server secrets in the Flutter client.
- Do not recreate billing, trial eligibility, usage limits or administrator authorization in client code.
- Keep Android's current package ID, signing identity and update path compatible when a Flutter Android release eventually replaces it.
- Preserve local chat history and settings through an explicit migration/import strategy.

## Migration sequence

1. Platform foundation: identify Android, iOS and Windows at runtime; define a capability profile so the UI does not promise unsupported platform features.
2. Service integration: map existing Firebase auth and Netlify request/response contracts before implementing client services.
3. Shared core: implement authenticated networking, AI chat, account status, usage/subscription state and safe error handling.
4. Feature parity: implement history, file attachments, voice, Work Centre and Admin Centre according to existing backend capabilities.
5. Native adapters: Android overlay/MediaProjection/notifications; iOS permitted capture/voice/file integrations; Windows desktop window, tray and capture integrations.
6. Validation: run Flutter tests and build checks; test on real Android, iOS and Windows devices.
7. Release: prepare Android APK/AAB, iOS TestFlight/App Store build and Windows installer. Publish only after explicit owner approval.

## Current scope and safeguards

- The existing Kotlin Android application remains the current production app.
- The Flutter branch is isolated at feature/flutter-cross-platform-foundation.
- The platform profile introduced in this phase describes intended capability boundaries; it does not itself implement native overlays, screen capture or privacy protection.
- The shared Flutter shell is not yet connected to Firebase or the AI backend and is not ready for installation.
- No merge, production deployment, store submission or replacement of the current Android app is authorized by this plan.