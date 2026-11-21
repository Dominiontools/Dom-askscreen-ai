import 'package:firebase_auth/firebase_auth.dart';

import 'id_token_provider.dart';

class AuthRepositoryException implements Exception {
  const AuthRepositoryException(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => message;
}

/// Uses the existing Firebase Authentication project and user identities.
/// Firebase must be initialized with the app's platform options before use.
class AuthRepository implements IdTokenProvider {
  AuthRepository({FirebaseAuth? firebaseAuth})
      : _auth = firebaseAuth ?? FirebaseAuth.instance;

  final FirebaseAuth _auth;

  User? get currentUser => _auth.currentUser;
  Stream<User?> get authStateChanges => _auth.authStateChanges();

  Future<UserCredential> signIn({required String email, required String password}) async {
    try {
      return await _auth.signInWithEmailAndPassword(email: email.trim(), password: password);
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  Future<UserCredential> createAccount({required String email, required String password}) async {
    try {
      final credential = await _auth.createUserWithEmailAndPassword(
        email: email.trim(),
        password: password,
      );
      await credential.user?.sendEmailVerification();
      return credential;
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

  Future<void> sendPasswordReset({required String email}) async {
    try {
      await _auth.sendPasswordResetEmail(email: email.trim());
    } on FirebaseAuthException catch (error) {
      throw _mapFirebaseError(error);
    }
  }

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