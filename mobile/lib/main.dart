import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'platform/platform_profile.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DomAskScreenApp());
}

class DomAskScreenApp extends StatelessWidget {
  const DomAskScreenApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DOM AskScreen AI',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF3157D5),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF7894FF),
          brightness: Brightness.dark,
        ),
      ),
      home: const MigrationFoundationScreen(),
    );
  }
}

class MigrationFoundationScreen extends StatelessWidget {
  const MigrationFoundationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final profile = PlatformProfile.detect(
      target: kIsWeb ? null : defaultTargetPlatform,
    );
    final platformName = switch (profile.platform) {
      DomPlatform.android => 'Android',
      DomPlatform.ios => 'iOS',
      DomPlatform.windows => 'Windows',
      DomPlatform.macos => 'macOS',
      DomPlatform.linux => 'Linux',
      DomPlatform.web => 'Web preview',
      DomPlatform.other => 'Other platform',
    };

    return Scaffold(
      appBar: AppBar(title: const Text('DOM AskScreen AI')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 620),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.auto_awesome, size: 44),
                const SizedBox(height: 20),
                const Text(
                  'One app. Three platforms.',
                  style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                Text('Detected platform: $platformName'),
                const SizedBox(height: 12),
                Text(profile.screenPrivacyDescription),
                const SizedBox(height: 20),
                const Text(
                  'The shared Flutter foundation is being prepared for '
                  'Android, iOS and Windows. Existing AI, accounts, '
                  'subscriptions, Work Centre, Admin Centre and device '
                  'features will be connected in staged migrations. '
                  'This foundation is not yet connected to production '
                  'services, and the existing Android app remains untouched.',
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
