# AskScreen AI — New repository package

This package preserves the supplied project source. It includes:
- Android application source (`app/`)
- Netlify website/backend project (`backend/`)
- AI request function (`backend/netlify/functions/ask-screen.js`)
- Android screen/audio capture, overlay UI, accessibility service, history and settings source
- GitHub Actions workflow to build a debug APK

## Upload
1. Create a new empty GitHub repository, for example `askscreen-ai`.
2. Extract this ZIP on your device/computer.
3. Upload the CONTENTS of this folder to the repository root (not the outer folder and not the ZIP itself).
4. Commit the files to the `main` branch.

## Build APK
1. Open the repository's Actions tab.
2. Choose "Build AskScreen AI APK".
3. Press "Run workflow".
4. When the workflow succeeds, download `askscreen-ai-debug-apk` from the run's Artifacts section.

## Website/backend
The Netlify project is in `backend/`. Deploy that folder as the Netlify site base directory, with the backend README's required environment variables configured in Netlify. Do not put AI provider secrets in Android source or commit them to GitHub.

## Important verification notes
This is the supplied source packaged with a build workflow; it is not a claim that all requested features are complete or tested. The current Android Gradle configuration declares namespace `com.example` and application ID `com.aistudio.askscreenai.vtwxpk`; do not assume older `com/askscreen/ai` file paths. Review package identifiers and Firebase/Netlify configuration before publishing.

The workflow creates a DEBUG APK for testing, not a signed production release. Test screen capture, microphone permissions, background behavior, accessibility, overlay positioning, duplicate detection and network failures on a real device. Desktop Windows/macOS/Linux installers are not included in this Android+Netlify source package and need a separate desktop app target.
