import 'package:flutter/material.dart';

import 'auth_repository.dart';
import 'auth_session.dart';

/// Shared sign-in and registration screen for Firebase-backed platform adapters.
class AuthScreen extends StatefulWidget {
  const AuthScreen({
    super.key,
    required this.repository,
    required this.onSignedIn,
  });

  final AuthSession repository;
  final ValueChanged<AppUser> onSignedIn;

  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _createAccount = false;
  bool _busy = false;
  bool _obscure = true;
  String? _message;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() { _busy = true; _error = null; _message = null; });
    try {
      final user = _createAccount
          ? await widget.repository.createAccount(email: _email.text, password: _password.text)
          : await widget.repository.signIn(email: _email.text, password: _password.text);
      if (!mounted) return;
      if (user.emailVerified) {
        widget.onSignedIn(user);
      } else {
        setState(() => _message =
            'Check your email and verify your address. Then select “I have verified my email”.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resetPassword() async {
    final email = _email.text.trim();
    if (email.isEmpty || !email.contains('@')) {
      setState(() => _error = 'Enter your email address first.');
      return;
    }
    setState(() { _busy = true; _error = null; _message = null; });
    try {
      await widget.repository.sendPasswordReset(email: email);
      if (mounted) {
        setState(() => _message =
            'Password reset instructions have been sent if the account exists.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resendVerification() async {
    setState(() { _busy = true; _error = null; _message = null; });
    try {
      await widget.repository.resendEmailVerification();
      if (mounted) {
        setState(() => _message = 'A new verification email has been sent.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _checkVerification() async {
    setState(() { _busy = true; _error = null; _message = null; });
    try {
      await widget.repository.reloadCurrentUser();
      final user = widget.repository.currentUser;
      if (!mounted) return;
      if (user?.emailVerified == true) {
        widget.onSignedIn(user!);
      } else {
        setState(() => _message =
            'Your email is not verified yet. Open the verification link, then try again.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final hasUnverifiedUser = widget.repository.currentUser != null &&
        !widget.repository.currentUser!.emailVerified;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 440),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Icon(Icons.auto_awesome, size: 48),
                    const SizedBox(height: 18),
                    Text(_createAccount ? 'Create your account' : 'Welcome back',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineMedium),
                    const SizedBox(height: 8),
                    const Text('Sign in to access DOM AskScreen AI on your devices.',
                      textAlign: TextAlign.center),
                    const SizedBox(height: 28),
                    TextFormField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
                      autofillHints: const [AutofillHints.email],
                      decoration: const InputDecoration(labelText: 'Email', border: OutlineInputBorder()),
                      validator: (v) => v == null || !v.contains('@') ? 'Enter a valid email.' : null,
                    ),
                    const SizedBox(height: 14),
                    TextFormField(
                      controller: _password,
                      obscureText: _obscure,
                      autofillHints: [_createAccount ? AutofillHints.newPassword : AutofillHints.password],
                      decoration: InputDecoration(
                        labelText: 'Password',
                        border: const OutlineInputBorder(),
                        suffixIcon: IconButton(
                          onPressed: () => setState(() => _obscure = !_obscure),
                          icon: Icon(_obscure ? Icons.visibility : Icons.visibility_off),
                        ),
                      ),
                      validator: (v) => v == null || v.length < 6 ? 'Use at least 6 characters.' : null,
                    ),
                    if (!_createAccount)
                      Align(
                        alignment: Alignment.centerRight,
                        child: TextButton(
                          onPressed: _busy ? null : _resetPassword,
                          child: const Text('Forgot password?'),
                        ),
                      ),
                    if (hasUnverifiedUser) ...[
                      TextButton(
                        onPressed: _busy ? null : _resendVerification,
                        child: const Text('Resend verification email'),
                      ),
                      OutlinedButton(
                        onPressed: _busy ? null : _checkVerification,
                        child: const Text('I have verified my email'),
                      ),
                    ],
                    if (_error != null) Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ),
                    if (_message != null) Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_message!, style: TextStyle(color: Theme.of(context).colorScheme.primary)),
                    ),
                    const SizedBox(height: 16),
                    FilledButton(
                      onPressed: _busy ? null : _submit,
                      child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) :
                        Text(_createAccount ? 'Create account' : 'Sign in'),
                    ),
                    TextButton(
                      onPressed: _busy ? null : () => setState(() {
                        _createAccount = !_createAccount; _error = null; _message = null;
                      }),
                      child: Text(_createAccount ? 'Already have an account? Sign in' : 'New here? Create an account'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
