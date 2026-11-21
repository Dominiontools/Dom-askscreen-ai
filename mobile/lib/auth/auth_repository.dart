import 'package:firebase_auth/firebase_auth.dart';

import 'auth_session.dart';

/// Firebase Auth adapter used by the existing Android and iOS clients.
class AuthRepository implements AuthSession {
  AuthRepository({FirebaseAuth? firebaseAuth})
      : _auth = firebaseAuth ?? FirebaseAuth.instance;

  final FirebaseAuth _auth;

  AppUser? _mapUser(User? user) => user == null
      ? null
      : AppUser(
          uid: user.uid,
          email: user.email ?? '',
          emailVerified: user.emailVerified,
        );

  @override
  AppUser? get currentUser => _mapUser(_auth.currentUser);

  @override
  Stream<AppUser?> get authStateChanges =>
      _auth.authStateChanges().map(_mapUser);

  @override
  Future<void> initialize() async {}

  @override
  Future<AppUser> signIn({required String email, required String password}) async {
    try {
      final credential = await _auth.signInWithEmailAndPassword(
        email: email.trim(),
        password: password,
      );
      return _mapUser(credential.user)!;
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<AppUser> createAccount({required String email, required String password}) async {
    try {
      final credential = await _auth.createUserWithEmailAndPassword(
        email: email.trim(),
        password: password,
      );
      await credential.user?.sendEmailVerification();
      return _mapUser(credential.user)!;
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<void> sendPasswordReset({required String email}) async {
    try {
      await _auth.sendPasswordResetEmail(email: email.trim());
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<void> resendEmailVerification() async {
    final user = _auth.currentUser;
    if (user == null) {
      throw const AuthRepositoryException('AUTH_REQUIRED', 'Please sign in first.');
    }
    try {
      await user.sendEmailVerification();
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<void> reloadCurrentUser() async {
    try {
      await _auth.currentUser?.reload();
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<String> requireIdToken({bool forceRefresh = false}) async {
    final user = _auth.currentUser;
    if (user == null) {
      throw const AuthRepositoryException('AUTH_REQUIRED', 'Please sign in to continue.');
    }
    try {
      final token = await user.getIdToken(forceRefresh);
      if (token == null || token.isEmpty) {
        throw const AuthRepositoryException(
          'TOKEN_UNAVAILABLE',
          'We could not verify your account. Please sign in again.',
        );
      }
      return token;
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  @override
  Future<void> signOut() => _auth.signOut();

  AuthRepositoryException _mapFirebaseError(FirebaseAuthException error) {
    final message = switch (error.code) {
      'invalid-email' => 'Enter a valid email address.',
      'user-disabled' => 'This account has been disabled. Contact support.',
      'user-not-found' || 'wrong-password' || 'invalid-credential' =>
        'The email or password is incorrect.',
      'email-already-in-use' => 'This email already has an account. Sign in instead.',
      'weak-password' => 'Choose a stronger password.',
      'too-many-requests' => 'Too many attempts. Wait a while and try again.',
      'network-request-failed' => 'Check your internet connection and try again.',
      'operation-not-allowed' =>
        'Email and password sign-in is not enabled for this Firebase project.',
      _ => 'Firebase could not complete the account request. Please try again.',
    };
    return AuthRepositoryException(error.code, message);
  }
}
