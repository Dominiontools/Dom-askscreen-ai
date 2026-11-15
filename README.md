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

## Self-hosted Android updates

DOM Ask AI is being prepared to distribute signed APK updates from its own Update Centre, without requiring Google Play.

- The Admin Centre provides a release workflow for registering an APK, entering its version code/name, release notes and SHA-256 checksum, and publishing the release metadata.
- Customer installations will check the published release, compare its version code with the installed app, and offer an **Update** action when a newer release is available.
- Android will still show its system installation approval when required. Ordinary apps cannot silently install an APK on a customer's personal phone.
- Every update must use the same application ID (`com.dominion.askscreenai`), the same release signing certificate/key as the installed customer app, and a strictly higher `versionCode`. A mismatched signature or package ID will cause Android to reject the update.
- The existing signing configuration must be preserved. Before publishing the first APK update, verify the signing certificate against the APK customers currently have; do not rotate or replace the keystore.
- Release uploads and metadata must be authorized by the server-side admin allowlist. Never expose service-account credentials or signing keys in the app or repository.

The self-hosted update workflow is not considered ready for customer distribution until APK upload, checksum verification, install approval, and an upgrade test from the currently distributed APK have all passed.

### Building a customer release APK safely

The GitHub Actions workflow always creates a **debug APK for testing only**. Never upload that debug APK to a public GitHub Release or use it to update customer installations. Debug signing is not the production signing identity.

To let GitHub Actions create a signed release APK, add these three repository Actions secrets under **GitHub → Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: the existing production upload keystore encoded as Base64.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_PASSWORD`: the password for the `upload` key alias.

On Windows PowerShell, encode the existing keystore with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\your-existing-upload-key.jks"))
```

Copy the output directly into the GitHub secret. Do not paste the keystore, passwords, or Base64 value into chat, source code, issues, or pull-request comments. Do not generate a new key if customers already have an app signed with an existing production key.

When all three secrets are present, Actions also builds and uploads the `askscreen-ai-release-apk` artifact. If they are absent, only the debug test artifact is produced. The Gradle release configuration currently expects the key alias `upload`.

Before using the release artifact, verify its signing certificate against the APK already installed by customers. The CI build passing does not prove that the signing key matches the distributed app. Keep the existing key backed up securely.

The Admin Centre requires the SHA-256 checksum of the exact APK asset. On Windows PowerShell, generate it with:

```powershell
Get-FileHash .\dom-ask-ai.apk -Algorithm SHA256
```

Copy the 64-character value shown in the `Hash` column into the Admin Centre. On every customer update, the app downloads the APK and verifies its SHA-256 value before opening Android's installer. A missing or mismatched checksum blocks installation.

Administrators can also manually grant or revoke an independent Work Centre product for a customer. Grants require a future expiry date (maximum two years ahead), are restricted to verified administrator accounts, and create an audit-log entry. This is a manual access override, not a payment record; Paystack payment activation remains deferred.

## Security

Do not commit `.env`, API keys, signing keys, or debug tokens. Configure secrets locally from `.env.example` or your deployment environment. Review the Firebase App Check and Google Services configuration for your own Firebase project before release.
