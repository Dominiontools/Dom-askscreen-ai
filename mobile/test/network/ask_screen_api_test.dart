import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:dom_askscreen_ai/network/ask_screen_api.dart';

class FakeTransport implements ApiTransport {
  FakeTransport(this.response);

  ApiHttpResponse response;
  Uri? lastUri;
  String? lastMethod;
  Map<String, String>? lastHeaders;
  String? lastBody;

  @override
  Future<ApiHttpResponse> send({
    required Uri uri,
    required String method,
    required Map<String, String> headers,
    String? body,
  }) async {
    lastUri = uri;
    lastMethod = method;
    lastHeaders = headers;
    lastBody = body;
    return response;
  }
}

void main() {
  final endpoint = Uri.parse('https://example.netlify.app/.netlify/functions/ask-screen');

  test('requires a secure AskScreen function URL', () {
    expect(
      () => AskScreenApi(askScreenEndpoint: Uri.parse('http://example.com/ask-screen')),
      throwsArgumentError,
    );
    expect(
      () => AskScreenApi(askScreenEndpoint: Uri.parse('https://example.com/other')),
      throwsArgumentError,
    );
  });

  test('sends authenticated request in the existing backend schema', () async {
    final transport = FakeTransport(const ApiHttpResponse(
      statusCode: 200,
      body: '{"success":true,"answer":"Hello from DOM AI"}',
    ));
    final api = AskScreenApi(askScreenEndpoint: endpoint, transport: transport);

    final answer = await api.ask(
      prompt: 'Explain photosynthesis',
      idToken: 'firebase-id-token',
      queryType: 'SCREEN',
      history: const [{'role': 'user', 'text': 'Previous question'}],
    );

    expect(answer, 'Hello from DOM AI');
    expect(transport.lastMethod, 'POST');
    expect(transport.lastUri, endpoint);
    expect(transport.lastHeaders?['Authorization'], 'Bearer firebase-id-token');
    final body = jsonDecode(transport.lastBody!) as Map<String, dynamic>;
    expect(body['prompt'], 'Explain photosynthesis');
    expect(body['queryType'], 'SCREEN');
    expect(body['history'], isA<List>());
  });

  test('requests account status from the sibling endpoint', () async {
    final transport = FakeTransport(const ApiHttpResponse(
      statusCode: 200,
      body: '{"email":"user@example.com","trial":{"eligible":true}}',
    ));
    final api = AskScreenApi(askScreenEndpoint: endpoint, transport: transport);

    final status = await api.getAccountStatus(idToken: 'firebase-id-token');

    expect(transport.lastMethod, 'GET');
    expect(transport.lastUri!.path, endsWith('/account-status'));
    expect(transport.lastHeaders?['Authorization'], 'Bearer firebase-id-token');
    expect(status['email'], 'user@example.com');
  });

  test('maps subscription and request ID from backend errors', () async {
    final transport = FakeTransport(const ApiHttpResponse(
      statusCode: 402,
      body: '{"success":false,"code":"SUBSCRIPTION_REQUIRED","requestId":"req-123"}',
    ));
    final api = AskScreenApi(askScreenEndpoint: endpoint, transport: transport);

    await expectLater(
      api.ask(prompt: 'Question', idToken: 'token'),
      throwsA(isA<AskScreenApiException>().having((e) => e.code, 'code', 'SUBSCRIPTION_REQUIRED')
        .having((e) => e.requestId, 'requestId', 'req-123')),
    );
  });
}