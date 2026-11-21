# Universal download page

The public download landing page lives at `backend/public/downloads/index.html` and is served by the existing Netlify site at `/downloads/` when the production branch is deployed.

## How it works

- The page detects Android, iOS, or Windows and highlights the matching platform.
- It reads `/downloads/manifest.json` to determine whether a public download is available.
- A platform button becomes clickable only when its manifest entry has `status: "available"` and a real `url`.
- Never put a temporary GitHub Actions artifact URL in this manifest. Those artifacts expire and are not public release downloads.
- Keep the page useful on desktop and mobile; users can always choose a different platform manually.

## Activating a platform download

After a distributable has been built, tested, and approved for release, update that platform entry in `manifest.json`:

```json
{
  "status": "available",
  "label": "Version 1.1.4 is ready",
  "buttonLabel": "Download for Android",
  "url": "https://your-verified-public-release-asset-url"
}
```

Use a verified, stable public release asset URL. Do not use a guessed URL or an expiring Actions artifact link.

## Platform release requirements

- **Android:** build a signed APK (or provide a Play Store listing). Preserve the existing package ID and signing/update identity so existing Android users are not stranded. Verify upgrade/install and sign-in before enabling the link.
- **Windows:** package a proper installer (for example MSIX or a signed setup executable), not just the raw Flutter runner folder. Test install, launch, sign-in, update and uninstall on a Windows machine.
- **iOS:** configure Apple signing and provisioning, then distribute through TestFlight or the App Store. An unsigned iOS build is only a CI compile check and cannot be installed by customers.
- **Shared accounts:** continue using the existing Firebase project and backend. Do not create a second account database or move subscription/trial decisions into the client.

## Safety and release control

This page and manifest are part of the development branch until explicitly approved and deployed. Keep download URLs unset until the corresponding package is verified and the user explicitly approves release. The production deployment, merge, app-store submission and public release remain separate approval steps.
