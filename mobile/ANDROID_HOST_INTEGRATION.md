# Android Host Integration Plan

## Decision

Integrate the Flutter experience into the existing native Android application using Flutter Add-to-App. Do not replace the root Android application with a newly generated standalone Flutter Android project.

## Why this is required

The existing Android host is already present at the repository root:

- Gradle application module: `app/`
- Android application ID: `com.dominion.askscreenai`
- Existing version: `1.1.3 (versionCode 6)`
- Native entry activity: `app/src/main/java/com/example/MainActivity.kt`
- Application class: `app/src/main/java/com/example/AskScreenApp.kt`
- Existing manifest declares the screen-capture, overlay, microphone, foreground-service, accessibility-service, and share-sheet integrations.
- Native service implementations live under `app/src/main/java/com/example/service/`.

The Flutter work currently lives in `mobile/` and contains Dart source, tests, and documentation. It does not yet contain generated Android, iOS, or Windows platform runners.

## Required integration sequence

1. Keep the existing root `app/` module, application ID, signing configuration, manifest declarations, and native services intact.
2. Complete and verify the Flutter module configuration in `mobile/`, including Firebase configuration for the existing Firebase project. Do not fabricate Firebase options or credentials.
3. Generate the Flutter module's platform integration files with the authorized Flutter SDK/FlutterFire setup. Do not generate a second standalone Android application that competes with the existing host.
4. Add the Flutter module to the existing Android Gradle build using the official Flutter Add-to-App integration approach.
5. Expose Flutter screens from the existing native host through a Flutter engine/fragment or activity route, while retaining native Kotlin ownership of MediaProjection, floating overlay, accessibility, share intents, and foreground-service lifecycle.
6. Reuse the existing Firebase project and backend account/subscription authority. Do not create a second user database or subscription system.
7. Add integration tests and build a debug APK in CI. Install and test on a real Android device before considering any release build.
8. Keep the integration on the feature branch until the user explicitly approves any merge or deployment.

## Release safeguards

- Do not change the existing production application ID or signing configuration.
- Do not overwrite the existing native launcher activity or remove existing manifest permissions/services.
- Do not increase the production version or publish an APK as part of this integration work.
- A successful Dart unit-test run is not evidence that the Android host builds or that screen capture/overlay features work.
- Do not merge, deploy, or release without explicit user approval.

## Current status

The Flutter Dart foundation and its automated tests are passing. Platform runners, Firebase Flutter configuration, native-to-Flutter integration, Android build verification, and real-device testing remain outstanding.
