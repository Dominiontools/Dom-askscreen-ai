import 'package:flutter/material.dart';

import '../services/ask_screen_service.dart';

/// Shared AI chat surface. Conversation history is kept in memory in this
/// foundation; durable history and attachments are added in later parity work.
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.service, required this.onSignOut});

  final AskScreenService service;
  final Future<void> Function() onSignOut;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatMessage {
  const _ChatMessage(this.text, {required this.fromUser});
  final String text;
  final bool fromUser;
}

class _ChatScreenState extends State<ChatScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final List<_ChatMessage> _messages = [];
  bool _sending = false;
  String? _error;
  Map<String, dynamic>? _accountStatus;
  bool _loadingAccountStatus = false;

  @override
  void initState() {
    super.initState();
    _loadAccountStatus();
  }

  Future<void> _loadAccountStatus() async {
    if (_loadingAccountStatus) return;
    setState(() => _loadingAccountStatus = true);
    try {
      final status = await widget.service.accountStatus();
      if (mounted) setState(() => _accountStatus = status);
    } catch (_) {
      // Account status is supplementary; a temporary status failure must not
      // prevent the user from using the AI chat.
    } finally {
      if (mounted) setState(() => _loadingAccountStatus = false);
    }
  }

  String _accountSummary() {
    final status = _accountStatus;
    if (status == null) return 'Checking account and usage…';
    final subscription = status['subscription'];
    final trial = status['trial'];
    final paid = subscription is Map && subscription['status'] == 'active';
    if (paid) return 'Subscription active';
    if (trial is Map && trial['eligible'] == true) {
      final remaining = trial['screenRemaining'];
      return remaining == null
          ? 'Free trial available'
          : 'Free screen answers remaining: $remaining';
    }
    return 'No active subscription';
  }

  @override
  void dispose() {
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    final prompt = _input.text.trim();
    if (prompt.isEmpty || _sending) return;
    setState(() {
      _messages.add(_ChatMessage(prompt, fromUser: true));
      _input.clear();
      _sending = true;
      _error = null;
    });
    try {
      final history = _messages
          .take(_messages.length - 1)
          .map((m) => {'role': m.fromUser ? 'user' : 'assistant', 'text': m.text})
          .toList();
      final answer = await widget.service.ask(prompt: prompt, history: history);
      if (mounted) setState(() => _messages.add(_ChatMessage(answer, fromUser: false)));
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _sending = false);
      if (_scroll.hasClients) {
        await _scroll.animateTo(_scroll.position.maxScrollExtent,
            duration: const Duration(milliseconds: 220), curve: Curves.easeOut);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('DOM AskScreen AI'),
        actions: [
          IconButton(
            tooltip: 'Refresh account status',
            onPressed: _loadingAccountStatus ? null : _loadAccountStatus,
            icon: _loadingAccountStatus
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.refresh),
          ),
          IconButton(tooltip: 'Sign out', onPressed: widget.onSignOut,
            icon: const Icon(Icons.logout)),
        ],
      ),
      body: Column(
        children: [
          if (_accountStatus != null || _loadingAccountStatus)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
              child: Card(
                child: ListTile(
                  dense: true,
                  leading: const Icon(Icons.account_circle_outlined),
                  title: const Text('Account and usage'),
                  subtitle: Text(_accountSummary()),
                ),
              ),
            ),
          Expanded(
            child: _messages.isEmpty
                ? const Center(child: Padding(
                    padding: EdgeInsets.all(24),
                    child: Text('What would you like help with today?', textAlign: TextAlign.center),
                  ))
                : ListView.builder(
                    controller: _scroll,
                    padding: const EdgeInsets.all(16),
                    itemCount: _messages.length + (_sending ? 1 : 0),
                    itemBuilder: (context, index) {
                      if (index == _messages.length) {
                        return const Padding(padding: EdgeInsets.all(12),
                          child: Align(alignment: Alignment.centerLeft, child: CircularProgressIndicator()));
                      }
                      final message = _messages[index];
                      return Align(
                        alignment: message.fromUser ? Alignment.centerRight : Alignment.centerLeft,
                        child: Container(
                          constraints: const BoxConstraints(maxWidth: 620),
                          margin: const EdgeInsets.symmetric(vertical: 6),
                          padding: const EdgeInsets.all(14),
                          decoration: BoxDecoration(
                            color: message.fromUser
                                ? Theme.of(context).colorScheme.primaryContainer
                                : Theme.of(context).colorScheme.surfaceContainerHighest,
                            borderRadius: BorderRadius.circular(16),
                          ),
                          child: SelectableText(message.text),
                        ),
                      );
                    },
                  ),
          ),
          if (_error != null) Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                children: [
                  Expanded(child: TextField(
                    controller: _input,
                    minLines: 1,
                    maxLines: 5,
                    textInputAction: TextInputAction.send,
                    onSubmitted: (_) => _send(),
                    decoration: const InputDecoration(hintText: 'Ask DOM AskScreen AI…', border: OutlineInputBorder()),
                  )),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    onPressed: _sending ? null : _send,
                    tooltip: 'Send message',
                    icon: const Icon(Icons.send),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
