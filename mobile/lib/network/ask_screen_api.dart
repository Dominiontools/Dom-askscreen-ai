import 'dart:convert';

import 'dart:io';

abstract interface class ApiTransport {
  Future<ApiHttpResponse> send({
    required Uri uri,
    required String method,
    required Map<String, String> headers,
    String? body,
  });
}

class ApiHttpResponse {
  const ApiHttpResponse({required this.statusCode, required this.body, this.headers = const {}});

  final int statusCode;
  final String body;
  final Map<String, String> headers;
}

class IoApiTransport implements ApiTransport {
  IoApiTransport({HttpClient? client}) : _client = client ?? HttpClient();

  final HttpClient _client;

  @override
  Future<ApiHttpResponse> send({
    required Uri uri,
    required String method,
    required Map<String, String> headers,
    String? body,
  }) async {
    final request = await _client.openUrl(method, uri).timeout(const Duration(seconds: 20));
    headers.forEach(request.headers.set);
    if (body != null) request.write(body);
    final response = await request.close().timeout(const Duration(seconds: 65));
    final responseBody = await utf8.decoder.bind(response).join();
    final responseHeaders = <String, String>{};
    response.headers.forEach((name, values) {
      responseHeaders[name.toLowerCase()] = values.join(', ');
    });
    return ApiHttpResponse(statusCode: response.statusCode, body: responseBody, headers: responseHeaders);
  }

  void close() => _client.close(force: true);
}

class AskScreenApiException implements Exception {
  const AskScreenApiException({
    required this.message,
    this.statusCode,
    this.code,
    this.requestId,
    this.retryAfterSeconds,
  });

  final String message;
  final int? statusCode;
  final String? code;
  final String? requestId;
  final int? retryAfterSeconds;

  @override
  String toString() => message;
}

/// Client for the existing authenticated Netlify AskScreen API.
/// The configured endpoint must be the full HTTPS URL ending in /ask-screen.
class AskScreenApi {
  AskScreenApi({required Uri askScreenEndpoint, ApiTransport? transport})
      : _endpoint = _validateEndpoint(askScreenEndpoint),
        _transport = transport ?? IoApiTransport();

  final Uri _endpoint;
  final ApiTransport _transport;

  static Uri _validateEndpoint(Uri endpoint) {
    if (endpoint.scheme != 'https' || endpoint.host.isEmpty ||
        !RegExp(r'/ask-screen/?$').hasMatch(endpoint.path)) {
      throw ArgumentError.value(
        endpoint,
        'askScreenEndpoint',
        'Must be an HTTPS Netlify function URL ending in /ask-screen.',
      );
    }
    return endpoint.replace(query: null, fragment: null);
  }

  static void _requireToken(String idToken) {
    if (idToken.trim().isEmpty) {
      throw const AskScreenApiException(
        message: 'Please sign in again to continue.',
        statusCode: 401,
        code: 'AUTH_REQUIRED',
      );
    }
  }

  Uri get _accountStatusUri => _endpoint.replace(
        path: _endpoint.path.replaceFirst(RegExp(r'/ask-screen/?$'), '/account-status'),
        query: null,
        fragment: null,
      );

  Future<String> ask({
    required String prompt,
    required String idToken,
    String model = 'gemini-3.8-flash',
    String queryType = 'SCREEN',
    List<Map<String, dynamic>> history = const [],
    List<Map<String, dynamic>> images = const [],
    List<Map<String, dynamic>> files = const [],
    Map<String, dynamic>? audio,
    String mode = 'NORMAL',
    Map<String, String>? taskContext,
  }) async {
    _requireToken(idToken);
    if (prompt.trim().isEmpty) {
      throw const AskScreenApiException(message: 'Please enter a question.');
    }
    final payload = <String, dynamic>{
      'prompt': prompt.trim(),
      'model': model,
      'queryType': queryType,
      'history': history,
      'images': images,
      'files': files,
      if (audio != null) 'audio': audio,
      'mode': mode,
      if (mode == 'SPECIFIC_TASK' && taskContext != null) 'taskContext': taskContext,
    };
    final response = await _transport.send(
      uri: _endpoint,
      method: 'POST',
      headers: {
        HttpHeaders.authorizationHeader: 'Bearer $idToken',
        HttpHeaders.contentTypeHeader: 'application/json; charset=utf-8',
        'X-Client-Version': 'flutter-foundation',
      },
      body: jsonEncode(payload),
    );
    final data = _decode(response.body);
    if (response.statusCode < 200 || response.statusCode >= 300 || data['success'] != true) {
      throw _apiError(response, data);
    }
    final answer = data['answer'];
    if (answer is! String || answer.trim().isEmpty) {
      throw const AskScreenApiException(message: 'The AI service returned an empty answer.');
    }
    return answer.trim();
  }

  Future<Map<String, dynamic>> getAccountStatus({required String idToken}) async {
    _requireToken(idToken);
    final response = await _transport.send(
      uri: _accountStatusUri,
      method: 'GET',
      headers: {
        HttpHeaders.authorizationHeader: 'Bearer $idToken',
        HttpHeaders.acceptHeader: 'application/json',
      },
    );
    final data = _decode(response.body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw _apiError(response, data);
    }
    return data;
  }

  Map<String, dynamic> _decode(String body) {
    try {
      final value = jsonDecode(body);
      if (value is Map<String, dynamic>) return value;
    } on FormatException {
      // Converted to a safe, user-facing API error below.
    }
    throw const AskScreenApiException(message: 'The server returned an unreadable response.');
  }

  AskScreenApiException _apiError(ApiHttpResponse response, Map<String, dynamic> data) {
    final code = data['code']?.toString();
    final requestId = data['requestId']?.toString() ?? response.headers['x-request-id'];
    final retry = int.tryParse(data['retryAfterSeconds']?.toString() ?? response.headers['retry-after'] ?? '');
    final message = switch (code) {
      'AUTH_REQUIRED' || 'INVALID_AUTH_TOKEN' => 'Please sign in again to continue.',
      'SUBSCRIPTION_REQUIRED' => 'Your free-use allowance has been used. A subscription is required to continue.',
      'RATE_LIMITED' => 'Too many requests. Please wait and try again.',
      'FEATURE_DISABLED' => 'This feature is temporarily unavailable.',
      'NO_PROVIDER_CONFIGURED' => 'The AI service is not configured on the server.',
      'NO_MULTIMODAL_PROVIDER' => 'This attachment needs an AI provider that supports this media type.',
      'UPSTREAM_UNAVAILABLE' => 'The AI service is temporarily unavailable. Please try again shortly.',
      _ => data['error']?.toString() ?? 'The server could not complete the request.',
    };
    return AskScreenApiException(
      message: message,
      statusCode: response.statusCode,
      code: code,
      requestId: requestId,
      retryAfterSeconds: retry,
    );
  }
}