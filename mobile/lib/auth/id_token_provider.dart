/// Supplies a short-lived Firebase ID token for protected backend requests.
abstract interface class IdTokenProvider {
  Future<String> requireIdToken({bool forceRefresh = false});
}