import 'package:flutter/foundation.dart';

/// Identifies the host platform and the capabilities the shared UI may expose.
///
/// This is a UI capability map, not a substitute for checking native API
/// permission or runtime availability. Native adapters must still report
/// whether a capability is actually available on the current device.
enum DomPlatform { android, ios, windows, macos, linux, web, other }

class PlatformProfile {
  const PlatformProfile({
    required this.platform,
    required this.supportsFloatingAssistant,
    required this.supportsSystemScreenCapture,
    required this.supportsNativeDesktopWindow,
    required this.supportsNativeMobileDistribution,
    required this.screenPrivacyDescription,
  });

  final DomPlatform platform;
  final bool supportsFloatingAssistant;
  final bool supportsSystemScreenCapture;
  final bool supportsNativeDesktopWindow;
  final bool supportsNativeMobileDistribution;
  final String screenPrivacyDescription;

  static PlatformProfile detect({TargetPlatform? target, bool isWeb = kIsWeb}) {
    if (isWeb) {
      return const PlatformProfile(
        platform: DomPlatform.web,
        supportsFloatingAssistant: false,
        supportsSystemScreenCapture: false,
        supportsNativeDesktopWindow: false,
        supportsNativeMobileDistribution: false,
        screenPrivacyDescription:
            'Web mode does not provide native cross-app screen privacy controls.',
      );
    }

    switch (target ?? defaultTargetPlatform) {
      case TargetPlatform.android:
        return const PlatformProfile(
          platform: DomPlatform.android,
          supportsFloatingAssistant: true,
          supportsSystemScreenCapture: true,
          supportsNativeDesktopWindow: false,
          supportsNativeMobileDistribution: true,
          screenPrivacyDescription:
              'Android screen capture and overlay features require native permissions and device-level testing.',
        );
      case TargetPlatform.iOS:
        return const PlatformProfile(
          platform: DomPlatform.ios,
          supportsFloatingAssistant: false,
          supportsSystemScreenCapture: true,
          supportsNativeDesktopWindow: false,
          supportsNativeMobileDistribution: true,
          screenPrivacyDescription:
              'iOS uses Apple-approved capture awareness and privacy handling; it cannot hide an ordinary app overlay from every external recording.',
        );
      case TargetPlatform.windows:
        return const PlatformProfile(
          platform: DomPlatform.windows,
          supportsFloatingAssistant: true,
          supportsSystemScreenCapture: true,
          supportsNativeDesktopWindow: true,
          supportsNativeMobileDistribution: false,
          screenPrivacyDescription:
              'Windows can use native desktop window and capture-protection integrations where supported.',
        );
      case TargetPlatform.macOS:
        return const PlatformProfile(
          platform: DomPlatform.macos,
          supportsFloatingAssistant: true,
          supportsSystemScreenCapture: true,
          supportsNativeDesktopWindow: true,
          supportsNativeMobileDistribution: false,
          screenPrivacyDescription:
              'macOS capabilities depend on native window APIs and user-granted screen-recording permissions.',
        );
      case TargetPlatform.linux:
        return const PlatformProfile(
          platform: DomPlatform.linux,
          supportsFloatingAssistant: false,
          supportsSystemScreenCapture: false,
          supportsNativeDesktopWindow: true,
          supportsNativeMobileDistribution: false,
          screenPrivacyDescription:
              'Linux support requires a separately validated window-manager and capture integration.',
        );
      case TargetPlatform.fuchsia:
        return const PlatformProfile(
          platform: DomPlatform.other,
          supportsFloatingAssistant: false,
          supportsSystemScreenCapture: false,
          supportsNativeDesktopWindow: false,
          supportsNativeMobileDistribution: false,
          screenPrivacyDescription:
              'This platform has no configured native privacy adapter.',
        );
    }
  }
}