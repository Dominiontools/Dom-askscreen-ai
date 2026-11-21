import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dom_askscreen_ai/auth/id_token_provider.dart';
import 'package:dom_askscreen_ai/chat/chat_screen.dart';
import 'package:dom_askscreen_ai/network/ask_screen_api.dart';
import 'package:dom_askscreen_ai/services/ask_screen_service.dart';

class FakeAskScreenService implements AskScreenService {
  FakeAskScreenService(this.status);

  final Map<String, dynamic> status;

  @override
  AskScreenApi get api => throw UnimplementedError();

  @override
  IdTokenProvider get tokens => throw UnimplementedError();

  @override
  Future<Map<String, dynamic>> accountStatus() async => status;

  @override
  Future<String> ask({
    required String prompt,
    String model = 'gemini-3.8-flash',
    String queryType = 'SCREEN',
    List<Map<String, dynamic>> history = const [],
    List<Map<String, dynamic>> images = const [],
    List<Map<String, dynamic>> files = const [],
    Map<String, dynamic>? audio,
  }) async => 'Test answer';
}

void main() {
  Future<void> openChat(WidgetTester tester, Map<String, dynamic> status) async {
    await tester.pumpWidget(MaterialApp(
      home: ChatScreen(
        service: FakeAskScreenService(status),
        onSignOut: () async {},
      ),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('shows active subscription from backend account status', (tester) async {
    await openChat(tester, {
      'subscription': {'status': 'active'},
      'trial': {'eligible': false, 'screenRemaining': 0},
    });

    expect(find.text('Account and usage'), findsOneWidget);
    expect(find.text('Subscription active'), findsOneWidget);
  });

  testWidgets('shows remaining free screen answers from backend account status', (tester) async {
    await openChat(tester, {
      'subscription': {'status': 'inactive'},
      'trial': {'eligible': true, 'screenRemaining': 2},
    });

    expect(find.text('Free screen answers remaining: 2'), findsOneWidget);
  });

  testWidgets('shows no active subscription when trial is unavailable', (tester) async {
    await openChat(tester, {
      'subscription': {'status': 'inactive'},
      'trial': {'eligible': false, 'screenRemaining': 0},
    });

    expect(find.text('No active subscription'), findsOneWidget);
  });
}
