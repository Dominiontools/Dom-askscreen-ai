import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/ask_screen_service.dart';

/// Shared typed-chat surface for Normal AI and Specific Task modes.
/// Screen, microphone and attachment inputs are not connected in this Flutter UI yet.
/// Task details are session-local until account-scoped persistence is added.
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.service, required this.onSignOut, this.accountId = ''});

  final AskScreenService service;
  final Future<void> Function() onSignOut;
  final String accountId;

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
  final _taskTitleEditor = TextEditingController();
  final _taskDescriptionEditor = TextEditingController();
  final _scroll = ScrollController();
  final List<_ChatMessage> _messages = [];
  bool _sending = false;
  bool _specificTaskMode = false;
  String _taskTitle = '';
  String _taskDescription = '';
  String? _error;
  Map<String, dynamic>? _accountStatus;
  bool _loadingAccountStatus = false;

  @override
  void initState() {
    super.initState();
    _loadAccountStatus();
    _loadSavedTask();
  }

  Future<void> _loadSavedTask() async {
    if (widget.accountId.trim().isEmpty) return;
    final preferences = await SharedPreferences.getInstance();
    final key = 'specific_task_${widget.accountId}_';
    final title = preferences.getString('${key}title') ?? '';
    final description = preferences.getString('${key}description') ?? '';
    final enabled = preferences.getBool('${key}enabled') ?? false;
    if (!mounted) return;
    setState(() {
      _taskTitle = title;
      _taskDescription = description;
      _specificTaskMode = enabled && title.isNotEmpty;
    });
  }

  Future<void> _saveTaskState({required bool enabled}) async {
    if (widget.accountId.trim().isEmpty) return;
    final preferences = await SharedPreferences.getInstance();
    final key = 'specific_task_${widget.accountId}_';
    await preferences.setString('${key}title', _taskTitle);
    await preferences.setString('${key}description', _taskDescription);
    await preferences.setBool('${key}enabled', enabled && _taskTitle.isNotEmpty);
  }

  Future<void> _loadAccountStatus() async {
    if (_loadingAccountStatus) return;
    setState(() => _loadingAccountStatus = true);
    try {
      final status = await widget.service.accountStatus();
      if (mounted) setState(() => _accountStatus = status);
    } catch (_) {
      // Account status is supplementary; a temporary failure must not block chat.
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

  Future<void> _editTask() async {
    _taskTitleEditor.text = _taskTitle;
    _taskDescriptionEditor.text = _taskDescription;
    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Set your specific task'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: _taskTitleEditor,
                autofocus: true,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(
                  labelText: 'Subject or task',
                  hintText: 'For example: Fibonacci Test',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _taskDescriptionEditor,
                minLines: 3,
                maxLines: 6,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(
                  labelText: 'Extra details (optional)',
                  hintText: 'Add instructions or useful context. You do not need to list possible questions.',
                  alignLabelWithHint: true,
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'The AI will use this subject to understand related questions automatically.',
                style: Theme.of(dialogContext).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () {
              final title = _taskTitleEditor.text.trim();
              if (title.isEmpty) return;
              Navigator.pop(dialogContext, {
                'title': title,
                'description': _taskDescriptionEditor.text.trim(),
              });
            },
            child: const Text('Activate task'),
          ),
        ],
      ),
    );
    if (result == null || !mounted) return;
    setState(() {
      _taskTitle = result['title']!;
      _taskDescription = result['description']!;
      _specificTaskMode = true;
      _error = null;
    });
    await _saveTaskState(enabled: true);
  }

  Future<void> _chooseMode(bool specific) async {
    if (specific) {
      if (_taskTitle.isEmpty) {
        await _editTask();
      } else {
        setState(() => _specificTaskMode = true);
        await _saveTaskState(enabled: true);
      }
    } else {
      setState(() => _specificTaskMode = false);
      await _saveTaskState(enabled: false);
    }
  }

  @override
  void dispose() {
    _input.dispose();
    _taskTitleEditor.dispose();
    _taskDescriptionEditor.dispose();
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
      final answer = await widget.service.ask(
        prompt: prompt,
        history: history,
        mode: _specificTaskMode ? 'SPECIFIC_TASK' : 'NORMAL',
        taskContext: _specificTaskMode
            ? {'title': _taskTitle, 'description': _taskDescription}
            : null,
      );
      if (mounted) setState(() => _messages.add(_ChatMessage(answer, fromUser: false)));
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _sending = false);
      if (_scroll.hasClients) {
        await _scroll.animateTo(
          _scroll.position.maxScrollExtent,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOut,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final modeLabel = _specificTaskMode ? 'Specific Task: $_taskTitle' : 'Normal AI';
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
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('AI mode', style: TextStyle(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<bool>(
                        segments: const [
                          ButtonSegment(value: false, label: Text('Normal AI'), icon: Icon(Icons.chat_bubble_outline)),
                          ButtonSegment(value: true, label: Text('Specific Task'), icon: Icon(Icons.track_changes)),
                        ],
                        selected: {_specificTaskMode},
                        onSelectionChanged: (selection) => _chooseMode(selection.first),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Expanded(child: Text(
                          modeLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall,
                        )),
                        if (_specificTaskMode)
                          TextButton.icon(
                            onPressed: _editTask,
                            icon: const Icon(Icons.edit, size: 16),
                            label: const Text('Edit task'),
                          ),
                      ],
                    ),
                    if (_specificTaskMode)
                      Text(
                        'Questions will be understood in the context of this subject. You do not need to list them.',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
          ),
          Expanded(
            child: _messages.isEmpty
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        _specificTaskMode
                            ? 'Ready to help with $_taskTitle. Type your question below.'
                            : 'What would you like help with today?',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  )
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
                    decoration: InputDecoration(
                      hintText: _specificTaskMode ? 'Ask about $_taskTitle…' : 'Ask DOM AskScreen AI…',
                      border: const OutlineInputBorder(),
                    ),
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
