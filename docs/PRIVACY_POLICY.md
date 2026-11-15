# DOM Ask AI — Privacy Policy

**Status:** Draft for the development build. This policy must be reviewed and completed by the operator before public commercial release.

**Effective date:** To be set at public launch.

## 1. Information handled

Depending on the features you choose to use, DOM Ask AI may process:

- Account details such as email address, Firebase user identifier, email-verification status and authentication timestamps.
- Screen images captured only after you start a screen-capture session and approve Android's system permission.
- Speech recognized from microphone input while you start a listening feature. Android's speech-recognition service may process audio to produce text.
- Text prompts, recognized speech, screen images, and files that you submit for AI analysis.
- Local conversation history stored on your device when the history setting is enabled.
- Usage counts, subscription/access status, support tickets, administrator actions, technical errors and request metadata.
- Device/app information needed for permissions, diagnostics, updates and service operation.

## 2. Why information is processed

Information is used to provide the assistant, authenticate accounts, enforce feature allowances, display account status, provide customer support, protect the service, diagnose failures, manage subscriptions/access and deliver app updates.

## 3. Screen and microphone capture

Screen capture is user-initiated and requires Android's MediaProjection approval. Microphone listening is user-initiated and requires microphone permission. The app uses Android speech recognition and microphone input; it does not directly capture another app's private/internal playback audio.

Do not capture or upload another person's private information without a lawful basis and any required notice or consent. Android may block secure, DRM-protected or otherwise restricted content.

## 4. AI processing and service providers

When you request AI analysis, the relevant prompt, recognized text, selected screen images or uploaded content may be sent to the configured DOM Ask AI backend and onward to the AI provider configured by the operator. Firebase is used for authentication and Firestore data; Netlify hosts backend functions. The final provider list and applicable provider privacy terms must be confirmed by the operator before launch.

The backend's activity log is designed to store technical metadata rather than customer prompts, screenshots, audio or uploaded file contents. However, the AI provider necessarily receives the content needed to answer your request, and its handling is governed by the provider's terms and settings.

## 5. Storage and retention

- Chat history is stored locally on your device only when the history setting is enabled.
- Account, usage, product-access, support and administrative records are stored in Firestore.
- Technical activity/error logs are stored in Firestore for service monitoring.
- Retention periods, backup deletion periods and final AI-provider retention settings must be configured and documented by the operator before launch.

Uninstalling the app may not delete server-side account or support records.

## 6. Sharing

Information may be processed by the service providers needed to operate the app (including Firebase, Netlify and the configured AI provider), or disclosed when legally required or necessary to protect the service and its users. The operator must not sell personal data or use it for unrelated advertising without a separate lawful basis and notice.

## 7. Your choices and requests

You can stop screen capture or microphone listening from the app controls/notification, deny Android permissions, disable local history, and stop using the service.

For access, correction, account deletion or privacy requests, contact **Dominiontools56@gmail.com**. The operator must verify the request and explain any records that must be retained by law. The current development build does not yet provide a self-service account deletion button.

## 8. Security

The backend is designed to verify Firebase identity and enforce administrator authorization server-side. Firestore client access is denied by the supplied rules, while trusted backend functions use Firebase Admin SDK. No internet service can guarantee absolute security; keep your account credentials private and report suspected misuse.

## 9. Children

The service is not designed for children under the minimum age required by applicable law. A parent or guardian should contact the operator if a child's information was submitted improperly.

## 10. Changes and contact

The operator may update this policy as the service changes. The launch version should identify the legal business/operator name, physical or registered contact details, applicable jurisdiction and effective date.

Privacy contact: **Dominiontools56@gmail.com**
