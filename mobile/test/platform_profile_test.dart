import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dom_askscreen_ai/platform/platform_profile.dart';

void main() {
  group('PlatformProfile.detect', () {
    test('Android exposes native overlay and capture integration points', () {
      final profile = PlatformProfile.detect(target: TargetPlatform.android);
      expect(profile.platform, DomPlatform.android);
      expect(profile.supportsFloatingAssistant, isTrue);
      expect(profile.supportsSystemScreenCapture, isTrue);
      expect(profile.supportsNativeMobileDistribution, isTrue);
    });

    test('iOS does not claim Android-style floating overlays', () {
      final profile = PlatformProfile.detect(target: TargetPlatform.iOS);
      expect(profile.platform, DomPlatform.ios);
      expect(profile.supportsFloatingAssistant, isFalse);
      expect(profile.supportsSystemScreenCapture, isTrue);
      expect(profile.screenPrivacyDescription, contains('cannot hide'));
    });

    test('Windows is treated as a desktop platform', () {
      final profile = PlatformProfile.detect(target: TargetPlatform.windows);
      expect(profile.platform, DomPlatform.windows);
      expect(profile.supportsNativeDesktopWindow, isTrue);
      expect(profile.supportsNativeMobileDistribution, isFalse);
    });

    test('web mode does not claim native capabilities', () {
      final profile = PlatformProfile.detect(
        target: TargetPlatform.android,
        isWeb: true,
      );
      expect(profile.platform, DomPlatform.web);
      expect(profile.supportsFloatingAssistant, isFalse);
      expect(profile.supportsSystemScreenCapture, isFalse);
    });
  });
}