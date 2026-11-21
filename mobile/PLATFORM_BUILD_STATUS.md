# Platform build status

The Flutter project has generated native project scaffolding for:

- Android: the existing native Kotlin host remains preserved; Add-to-App compatibility is checked separately.
- iOS: the Xcode Runner project and workspace are generated under `ios/`.
- Windows: the CMake desktop runner and Flutter plugin registration are generated under `windows/`.

## Build validation

The `.github/workflows/flutter-platform-builds.yml` workflow runs shared Dart analysis and tests, then attempts:

- Windows release compilation on a Windows runner.
- iOS release compilation without code signing on a macOS runner.

A successful scaffold-generation workflow only confirms that the native project folders were generated and committed. It does not mean Firebase is configured, the app is authenticated, or a distributable app is ready.

## Required before platform release

1. Register each platform app in the existing Firebase project using the identifiers documented in `FIREBASE_SETUP.md`.
2. Generate and review platform Firebase client configuration with an authorized Firebase account.
3. Confirm Windows Firebase Authentication support for the selected implementation; do not ship a UI that cannot sign in.
4. Supply the verified existing Netlify AskScreen endpoint at build time.
5. Test sign-in, verified-email gating, AI requests, account limits and task context on real devices.
6. Implement and test platform-specific capture, microphone, privacy and window behavior. Do not assume Android overlay or capture permissions exist on iOS or Windows.

The current iOS and Windows folders are starter scaffolding, not completed feature implementations.
