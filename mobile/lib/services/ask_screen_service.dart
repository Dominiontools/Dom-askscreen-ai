import '../auth/id_token_provider.dart';
import '../network/ask_screen_api.dart';

/// Coordinates Firebase identity with the existing authenticated Netlify API.
class AskScreenService {
  const AskScreenService({required this.api, required this.tokens});

  final AskScreenApi api;
  final IdTokenProvider tokens;

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
    final idToken = await tokens.requireIdToken();
    return api.ask(
      prompt: prompt,
      idToken: idToken,
      model: model,
      queryType: queryType,
      history: history,
      images: images,
      files: files,
      audio: audio,
      mode: mode,
      taskContext: taskContext,
    );
  }

  Future<Map<String, dynamic>> accountStatus() async {
    final idToken = await tokens.requireIdToken(forceRefresh: true);
    return api.getAccountStatus(idToken: idToken);
  }
}