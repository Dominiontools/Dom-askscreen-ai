# AskScreen AI — consolidated update

## Included

- Reworked `ScreenCaptureManager` to retain one `MediaProjection` / `VirtualDisplay` per active grant, resize the existing display after rotation, and release resources when Android revokes the session.
- Added opt-in screen monitoring with stable-screen-change sampling and notification controls: Auto Start, Pause, Resume, Stop Auto, and Stop Assistant.
- Added a first-screen check when monitoring starts and a prompt guard that suppresses `NO_NEW_QUESTION` responses.
- Added continuous microphone speech recognition by restarting Android speech-recognition requests after each final result or recoverable error.
- Added an optional Android Accessibility Service for matching a multiple-choice answer and clicking an explicitly labelled Submit/Check control. It is disabled by default and requires a separate Android Accessibility opt-in.
- Added Settings controls for auto-submit and screen-monitor interval.
- Tightened AI instructions to concise multiple-choice answers, concise direct answers for other questions, and no guessing when text is unclear.
- Kept the existing floating answer window drag, resize, persisted size/position, and `SOFT_INPUT_ADJUST_RESIZE` behavior.
- Added usage, privacy, build, and compatibility notes to the README.

## Important limitations

- This is an Android source project, not a ready-to-install APK. The supplied project does not contain `gradlew`, `gradlew.bat`, or the Gradle wrapper JAR, and this environment does not have Gradle or an Android SDK installed. An Android Studio build and physical-device test are still required.
- The accessibility bridge is best-effort. It can only operate on visible text and controls exposed through Android's accessibility tree. Canvas-rendered/custom controls, protected screens, and some browser/app implementations will not work. It only clicks a clearly labelled Submit/Check control after matching a lettered option.
- Auto-submit is off by default. The user must enable it in AskScreen settings and separately enable AskScreen AI in Android Accessibility settings. Use only on practice/self-study pages where automation is allowed.
- Continuous audio is microphone-based Android speech recognition. It does not capture internal app/system playback audio; Android playback capture has separate platform, app opt-in, and foreground-service constraints and is not included here.
- Auto-monitoring samples screen pixels to detect stable changes. It can miss a question if the change is too small, and animated screens can cause unnecessary checks. Adjust the interval in Settings and pause monitoring when not needed.
- Android may stop foreground work, revoke MediaProjection, or restrict microphone access depending on OS version, battery settings, and how the service was started. A revoked capture session requires a new system permission grant.
- The existing AI model identifiers and Firebase configuration should be verified against the user's Firebase project and currently supported model names before release.

## Security

The archive intentionally excludes `.env` and any populated API secrets. Do not commit `.env`, signing keys, debug tokens, or private credentials. Configure secrets locally and rotate any credential that may have been exposed in a shared archive.
