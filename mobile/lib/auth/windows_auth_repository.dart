import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'auth_session.dart';

/// Firebase Authentication REST client for Windows desktop.
/// Uses the same Firebase project and accounts as the Android/iOS clients.
class WindowsAuthRepository implements AuthSession {
  WindowsAuthRepository({
    FlutterSecureStorage? storage,
    HttpClient? client,
  })  : _storage = storage ?? const FlutterSecureStorage(),
        _client = client ?? (HttpClient()..connectionTimeout = const Duration(seconds: 12));

  static const _operationTimeout = Duration(seconds: 15);
  static const _storageTimeout = Duration(seconds: 8);

  static const _apiKey = 'AIzaSyA_mckfSTP3d0tSsIqxR9a2jjF6vV6N6go';
  static const _refreshKey = 'firebase_refresh_token';
  static const _identityHost = 'identitytoolkit.googleapis.com';
  static const _tokenHost = 'securetoken.googleapis.com';

  final FlutterSecureStorage _storage;
  final HttpClient _client;
  final StreamController<AppUser?> _authChanges =
      StreamController<AppUser?>.broadcast();

  AppUser? _user;
  String? _idToken;
  String? _refreshToken;
  DateTime? _tokenExpiresAt;
  bool _initialized = false;

  @override
  AppUser? get currentUser => _user;

  @override
  Stream<AppUser?> get authStateChanges => _authChanges.stream;

  @override
  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;
    try {
      _refreshToken = await _storage
          .read(key: _refreshKey)
          .timeout(_storageTimeout);
      if (_refreshToken == null || _refreshToken!.isEmpty) {
        _authChanges.add(null);
        return;
      }
      await _refreshIdToken();
      await reloadCurrentUser();
    } catch (_) {
      // A slow/unavailable network or Windows credential store must never
      // leave the desktop app on its startup spinner indefinitely. Start with
      // a signed-out session so the user can sign in again.
      _idToken = null;
      _refreshToken = null;
      _tokenExpiresAt = null;
      _user = null;
      try {
        await _storage.delete(key: _refreshKey).timeout(_storageTimeout);
      } catch (_) {
        // The next successful sign-in will replace the stored token.
      }
      _authChanges.add(null);
    }
  }

  @override
  Future<AppUser> signIn({
    required String email,
    required String password,
  }) async {
    final data = await _identityPost('accounts:signInWithPassword', {
      'email': email.trim(),
      'password': password,
      'returnSecureToken': true,
    });
    await _acceptTokenResponse(data);
    await reloadCurrentUser();
    return _user!;
  }

  @override
  Future<AppUser> createAccount({
    required String email,
    required String password,
  }) async {
    final data = await _identityPost('accounts:signUp', {
      'email': email.trim(),
      'password': password,
      'returnSecureToken': true,
    });
    await _acceptTokenResponse(data);
    await resendEmailVerification();
    _user = AppUser(
      uid: data['localId'] as String,
      email: (data['email'] as String?) ?? email.trim(),
      emailVerified: false,
    );
    _authChanges.add(_user);
    return _user!;
  }

  @override
  Future<void> sendPasswordReset({required String email}) async {
    await _identityPost('accounts:sendOobCode', {
      'requestType': 'PASSWORD_RESET',
      'email': email.trim(),
    });
  }

  @override
  Future<void> resendEmailVerification() async {
    final token = await requireIdToken();
    await _identityPost('accounts:sendOobCode', {
      'requestType': 'VERIFY_EMAIL',
      'idToken': token,
    });
  }

  @override
  Future<void> reloadCurrentUser() async {
    final token = await requireIdToken(forceRefresh: true);
    final data = await _identityPost('accounts:lookup', {'idToken': token});
    final users = data['users'];
    if (users is! List || users.isEmpty || users.first is! Map) {
      throw const AuthRepositoryException(
        'USER_NOT_FOUND',
        'Your Firebase account could not be loaded. Please sign in again.',
      );
    }
    final record = Map<String, dynamic>.from(users.first as Map);
    _user = AppUser(
      uid: record['localId'] as String,
      email: (record['email'] as String?) ?? '',
      emailVerified: record['emailVerified'] == true,
    );
    _authChanges.add(_user);
  }

  @override
  Future<String> requireIdToken({bool forceRefresh = false}) async {
    if (_refreshToken == null || _refreshToken!.isEmpty) {
      throw const AuthRepositoryException(
        'AUTH_REQUIRED',
        'Please sign in to continue.',
      );
    }
    final expiresSoon = _tokenExpiresAt == null ||
        DateTime.now().isAfter(
          _tokenExpiresAt!.subtract(const Duration(minutes: 1)),
        );
    if (forceRefresh || _idToken == null || expiresSoon) {
      await _refreshIdToken();
    }
    return _idToken!;
  }

  Future<void> _refreshIdToken() async {
    final refresh = _refreshToken;
    if (refresh == null || refresh.isEmpty) {
      throw const AuthRepositoryException('AUTH_REQUIRED', 'Please sign in again.');
    }
    final uri = Uri.https(_tokenHost, '/v1/token', {'key': _apiKey});
    final request = await _client.postUrl(uri).timeout(_operationTimeout);
    request.headers.contentType = ContentType('application', 'x-www-form-urlencoded');
    request.write('grant_type=refresh_token&refresh_token=${Uri.encodeQueryComponent(refresh)}');
    final response = await request.close().timeout(_operationTimeout);
    final body = await utf8.decoder.bind(response).join().timeout(_operationTimeout);
    final data = _decode(body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw _firebaseError(data);
    }
    _idToken = data['id_token'] as String?;
    _refreshToken = (data['refresh_token'] as String?) ?? refresh;
    final expiresIn = int.tryParse(data['expires_in']?.toString() ?? '') ?? 3600;
    _tokenExpiresAt = DateTime.now().add(Duration(seconds: expiresIn));
    await _storage.write(key: _refreshKey, value: _refreshToken).timeout(_storageTimeout);
    if (_idToken == null || _idToken!.isEmpty) {
      throw const AuthRepositoryException('TOKEN_UNAVAILABLE', 'Firebase did not return a sign-in token.');
    }
  }

  Future<void> _acceptTokenResponse(Map<String, dynamic> data) async {
    _idToken = data['idToken'] as String?;
    _refreshToken = data['refreshToken'] as String?;
    final expiresIn = int.tryParse(data['expiresIn']?.toString() ?? '') ?? 3600;
    _tokenExpiresAt = DateTime.now().add(Duration(seconds: expiresIn));
    if (_idToken == null || _refreshToken == null) {
      throw const AuthRepositoryException(
        'TOKEN_UNAVAILABLE',
        'Firebase did not return the account session. Please try again.',
      );
    }
    await _storage.write(key: _refreshKey, value: _refreshToken);
  }

  Future<Map<String, dynamic>> _identityPost(
    String method,
    Map<String, dynamic> payload,
  ) async {
    final uri = Uri.https(_identityHost, '/v1/$method', {'key': _apiKey});
    final request = await _client.postUrl(uri);
    request.headers.contentType = ContentType.json;
    request.write(jsonEncode(payload));
    final response = await request.close();
    final data = _decode(await utf8.decoder.bind(response).join().timeout(_operationTimeout));
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw _firebaseError(data);
    }
    return data;
  }

  Map<String, dynamic> _decode(String body) {
    try {
      final value = jsonDecode(body);
      if (value is Map<String, dynamic>) return value;
    } on FormatException {
      // Convert unreadable responses to a safe authentication error.
    }
    throw const AuthRepositoryException(
      'INVALID_RESPONSE',
      'Firebase returned an unreadable response. Please try again.',
    );
  }

  AuthRepositoryException _firebaseError(Map<String, dynamic> data) {
    final error = data['error'];
    final code = error is Map ? error['message']?.toString() : null;
    final message = switch (code) {
      'EMAIL_EXISTS' => 'This email already has an account. Sign in instead.',
      'EMAIL_NOT_FOUND' || 'INVALID_PASSWORD' || 'INVALID_LOGIN_CREDENTIALS' =>
        'The email or password is incorrect.',
      'INVALID_EMAIL' => 'Enter a valid email address.',
      'WEAK_PASSWORD' => 'Choose a stronger password.',
      'USER_DISABLED' => 'This account has been disabled. Contact support.',
      'TOO_MANY_ATTEMPTS_TRY_LATER' => 'Too many attempts. Wait a while and try again.',
      'OPERATION_NOT_ALLOWED' => 'Email and password sign-in is not enabled in Firebase.',
      'INVALID_REFRESH_TOKEN' || 'TOKEN_EXPIRED' => 'Your session expired. Please sign in again.',
      _ => 'Firebase could not complete the account request. Please try again.',
    };
    return AuthRepositoryException(code ?? 'FIREBASE_AUTH_ERROR', message);
  }

  Future<void> _clearSession() async {
    _idToken = null;
    _refreshToken = null;
    _tokenExpiresAt = null;
    _user = null;
    await _storage.delete(key: _refreshKey).timeout(_storageTimeout);
    _authChanges.add(null);
  }

  @override
  Future<void> signOut() => _clearSession();
}
