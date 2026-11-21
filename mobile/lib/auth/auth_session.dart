import 'id_token_provider.dart';

class AppUser {
  const AppUser({
    required this.uid,
    required this.email,
    required this.emailVerified,
  });

  final String uid;
  final String email;
  final bool emailVerified;
}

abstract interface class AuthSession implements IdTokenProvider {
  Future<void> initialize();
  AppUser? get currentUser;
  Stream<AppUser?> get authStateChanges;
  Future<AppUser> signIn({required String email, required String password});
  Future<AppUser> createAccount({required String email, required String password});
  Future<void> sendPasswordReset({required String email});
  Future<void> resendEmailVerification();
  Future<void> reloadCurrentUser();
  Future<void> signOut();
}
