<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/39ea7161-a90e-403a-b87c-d7468dcafac7

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio and choose **Open** for this project folder.
2. Install Android SDK 36 and use a compatible Android Gradle Plugin / Kotlin toolchain.
3. The archive includes `gradle-wrapper.properties` but not the wrapper scripts or wrapper JAR. If Android Studio does not generate them, install Gradle 9.3.1 and run `gradle wrapper --gradle-version 9.3.1` from the project root.
4. For the optional Netlify backend, configure `GEMINI_API_KEY` in Netlify environment variables. Do not put private keys in source control. The app's default Firebase AI Logic path also requires valid Firebase configuration.
5. Build and test on a physical Android device before distributing. Do not remove the signing configuration without first understanding the signing setup for your existing app.


## Session monitoring and practice automation update

- Screen capture uses one `MediaProjection` grant and retains one `VirtualDisplay` for that session. Android may revoke the session; if it does, start a new capture session and approve the system prompt again.
- Use the persistent AskScreen AI notification's **Auto Start**, **Pause**, and **Resume** actions to monitor screen changes. Monitoring samples a small image grid and analyses a stable changed screen; it is not OCR-based event detection and may miss changes or react to animated content.
- Microphone listening now restarts speech recognition after each completed phrase. It uses Android's speech-recognition service and microphone input; it does **not** capture internal app/system playback audio.
- Automatic option selection is disabled by default. Enable it in Settings, then enable AskScreen AI under Android Settings → Accessibility. It works only when the target app exposes readable option text and clickable controls, and only when a clearly labelled Submit/Check button is available. Some apps and browsers do not expose those controls.
- Use automatic interaction only on practice or self-study pages where the site permits it. Keep manual control available; stop the assistant from its notification at any time.

## Voice and transcription

- The floating assistant includes continuous microphone listening, combined screen-and-microphone mode, and a transcript-only mode that adds recognized speech to the chat without asking the AI to answer it.
- Speech language can be selected in Settings: Device default, English (Nigeria), or Yoruba (Nigeria). Actual language availability and recognition quality depend on the Android speech-recognition service installed on the device.
- These features use microphone input. They do not directly capture another app's private/internal playback audio; use an available system-supported audio capture route where permitted.

## Backend diagnostics

- The optional Netlify backend exposes a read-only health endpoint at /.netlify/functions/health.
- It reports service status and which provider integrations are configured, without returning API keys or other secret values.
- Backend request validation, rate-limit behavior, health checks, and Android build are included in the upgrade-branch CI workflow.

## Build

Open the project folder in Android Studio and allow Gradle sync. The project targets SDK 36 and requires a compatible Android Gradle Plugin, Kotlin plugin, Android SDK 36, and Firebase configuration. The supplied archive does not include the Gradle wrapper JAR or a built APK, so this archive should be treated as source code, not an installable package. Build and test on a physical Android device, including Android 14+ MediaProjection behavior, accessibility interaction, rotation, microphone permissions, and background restrictions.

## Security

Do not commit `.env`, API keys, signing keys, or debug tokens. Configure secrets locally from `.env.example` or your deployment environment. Review the Firebase App Check and Google Services configuration for your own Firebase project before release.
