import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'auth/auth_repository.dart';
import 'auth/auth_screen.dart';
import 'auth/auth_session.dart';
import 'auth/windows_auth_repository.dart';
import 'chat/chat_screen.dart';
import 'network/ask_screen_api.dart';
import 'services/ask_screen_service.dart';

const _askScreenEndpoint = String.fromEnvironment(
  'ASK_SCREEN_ENDPOINT',
  defaultValue: 'https://domaskscreenai.netlify.app/.netlify/functions/ask-screen',
);

Future<void> _initializePlatform() async {
  if (defaultTargetPlatform == TargetPlatform.windows) return;
  await Firebase.initializeApp();
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DomAskScreenApp());
}

class DomAskScreenApp extends StatelessWidget {
  const DomAskScreenApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'DOM AskScreen AI',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF3157D5)),
          useMaterial3: true,
        ),
        darkTheme: ThemeData(
          colorScheme: ColorScheme.fromSeed(
            seedColor: const Color(0xFF7894FF),
            brightness: Brightness.dark,
          ),
          useMaterial3: true,
        ),
        home: FutureBuilder<void>(
          future: _initializePlatform(),
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const _LoadingScreen();
            }
            if (snapshot.hasError) {
              return _SetupErrorScreen(
                message: 'Firebase is not configured for this platform yet. '
                    'Complete mobile/FIREBASE_SETUP.md and rebuild. '
                    'Details: ${snapshot.error}',
              );
            }
            return const _AuthenticatedApp();
          },
        ),
      );
}

class _AuthenticatedApp extends StatefulWidget {
  const _AuthenticatedApp();

  @override
  State<_AuthenticatedApp> createState() => _AuthenticatedAppState();
}

class _AuthenticatedAppState extends State<_AuthenticatedApp> {
  late final AuthSession _auth;
  late final Future<void> _authInitialization;
  late final AskScreenService? _service;
  String? _configurationError;

  @override
  void initState() {
    super.initState();
    _auth = defaultTargetPlatform == TargetPlatform.windows
        ? WindowsAuthRepository()
        : AuthRepository();
    _authInitialization = _auth.initialize();
    if (_askScreenEndpoint.isEmpty) {
      _configurationError = 'The AI endpoint is not configured. Set '
          '--dart-define=ASK_SCREEN_ENDPOINT to the existing HTTPS Netlify '
          'function URL ending in /ask-screen.';
      _service = null;
    } else {
      try {
        _service = AskScreenService(
          api: AskScreenApi(askScreenEndpoint: Uri.parse(_askScreenEndpoint)),
          tokens: _auth,
        );
      } catch (error) {
        _configurationError = 'The AI endpoint configuration is invalid: $error';
        _service = null;
      }
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<void>(
        future: _authInitialization,
        builder: (context, initialization) {
          if (initialization.connectionState != ConnectionState.done) {
            return const _LoadingScreen();
          }
          if (initialization.hasError) {
            return _SetupErrorScreen(message: 'Could not restore your sign-in: ${initialization.error}');
          }
          return StreamBuilder<AppUser?>(
            stream: _auth.authStateChanges,
            initialData: _auth.currentUser,
            builder: (context, snapshot) {
              final user = snapshot.data;
              if (snapshot.connectionState == ConnectionState.waiting && user == null) {
                return const _LoadingScreen();
              }
              if (user == null || !user.emailVerified) {
                return AuthScreen(
                  repository: _auth,
                  onSignedIn: (_) => setState(() {}),
                );
              }
              if (_configurationError != null) {
                return _SetupErrorScreen(message: _configurationError!);
              }
              return ChatScreen(
                service: _service!,
                onSignOut: _auth.signOut,
                accountId: user.uid,
              );
            },
          );
        },
      );
}

class _LoadingScreen extends StatelessWidget {
  const _LoadingScreen();

  @override
  Widget build(BuildContext context) => const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
}

class _SetupErrorScreen extends StatelessWidget {
  const _SetupErrorScreen({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.settings_suggest_outlined, size: 48),
                  const SizedBox(height: 16),
                  Text('Setup required',
                      style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 12),
                  SelectableText(message, textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        ),
      );
}
