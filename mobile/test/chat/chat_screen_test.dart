import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dom_askscreen_ai/auth/id_token_provider.dart';
import 'package:dom_askscreen_ai/chat/chat_screen.dart';
import 'package:dom_askscreen_ai/network/ask_screen_api.dart';
import 'package:dom_askscreen_ai/services/ask_screen_service.dart';

class FakeAskScreenService implements AskScreenService {
  FakeAskScreenService(this.status);

  final Map<String, dynamic> status;
  String? lastMode;
  Map<String, String>? lastTaskContext;

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
    String mode = 'NORMAL',
    Map<String, String>? taskContext,
  }) async {
    lastMode = mode;
    lastTaskContext = taskContext;
    return 'Test answer';
  }
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

  testWidgets('starts in Normal AI mode', (tester) async {
    await openChat(tester, {});
    expect(find.text('Normal AI'), findsWidgets);
    expect(find.text('What would you like help with today?'), findsOneWidget);
  });

  testWidgets('activates a specific task and sends its context', (tester) async {
    final service = FakeAskScreenService({});
    await tester.pumpWidget(MaterialApp(
      home: ChatScreen(service: service, onSignOut: () async {}),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Specific Task').first);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'For example: Fibonacci Test'), 'Fibonacci Test');
    await tester.tap(find.text('Activate task'));
    await tester.pumpAndSettle();

    expect(find.text('Specific Task: Fibonacci Test'), findsOneWidget);
    await tester.enterText(find.byType(TextField).last, 'What is the Fibonacci sequence?');
    await tester.tap(find.byTooltip('Send message'));
    await tester.pumpAndSettle();

    expect(service.lastMode, 'SPECIFIC_TASK');
    expect(service.lastTaskContext?['title'], 'Fibonacci Test');
    expect(find.text('Test answer'), findsOneWidget);
  });

  testWidgets('returns to Normal AI mode', (tester) async {
    await openChat(tester, {});
    await tester.tap(find.text('Specific Task').first);
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'For example: Fibonacci Test'), 'Fibonacci');
    await tester.tap(find.text('Activate task'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Normal AI').first);
    await tester.pumpAndSettle();
    expect(find.text('What would you like help with today?'), findsOneWidget);
  });
}
