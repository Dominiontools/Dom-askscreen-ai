import 'package:flutter_test/flutter_test.dart';
import 'package:dom_askscreen_ai/auth/id_token_provider.dart';
import 'package:dom_askscreen_ai/network/ask_screen_api.dart';
import 'package:dom_askscreen_ai/services/ask_screen_service.dart';

class FakeTokenProvider implements IdTokenProvider {
  @override
  Future<String> requireIdToken({bool forceRefresh = false}) async =>
      forceRefresh ? 'refreshed-token' : 'current-token';
}

class FakeTransport implements ApiTransport {
  FakeTransport(this.response);
  final ApiHttpResponse response;
  String? authorization;
  String? method;

  @override
  Future<ApiHttpResponse> send({
    required Uri uri,
    required String method,
    required Map<String, String> headers,
    String? body,
  }) async {
    this.method = method;
    authorization = headers['Authorization'];
    return response;
  }
}

void main() {
  test('AI service gets a Firebase token and forwards it to backend', () async {
    final transport = FakeTransport(const ApiHttpResponse(
      statusCode: 200,
      body: '{"success":true,"answer":"Ready"}',
    ));
    final service = AskScreenService(
      api: AskScreenApi(
        askScreenEndpoint: Uri.parse('https://sample.netlify.app/.netlify/functions/ask-screen'),
        transport: transport,
      ),
      tokens: FakeTokenProvider(),
    );

    expect(await service.ask(prompt: 'Hello'), 'Ready');
    expect(transport.authorization, 'Bearer current-token');
    expect(transport.method, 'POST');
  });

  test('account status forces a fresh Firebase token', () async {
    final transport = FakeTransport(const ApiHttpResponse(
      statusCode: 200,
      body: '{"subscription":{"status":"inactive"}}',
    ));
    final service = AskScreenService(
      api: AskScreenApi(
        askScreenEndpoint: Uri.parse('https://sample.netlify.app/.netlify/functions/ask-screen'),
        transport: transport,
      ),
      tokens: FakeTokenProvider(),
    );

    await service.accountStatus();
    expect(transport.authorization, 'Bearer refreshed-token');
    expect(transport.method, 'GET');
  });
}