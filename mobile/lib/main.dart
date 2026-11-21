import 'package:flutter/material.dart';

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
        useMaterial3: true,
      ),
      home: const MigrationFoundationScreen(),
    );
  }
}

class MigrationFoundationScreen extends StatelessWidget {
  const MigrationFoundationScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('DOM AskScreen AI'),
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: const Padding(
            padding: EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.auto_awesome, size: 44),
                SizedBox(height: 20),
                Text(
                  'Cross-platform foundation',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                SizedBox(height: 12),
                Text(
                  'This is the new shared Flutter application shell. '
                  'Existing AI, accounts, subscriptions, Work Centre, '
                  'Admin Centre and device features will be connected '
                  'in staged migrations. No production services or '
                  'existing Android code have been replaced.',
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
