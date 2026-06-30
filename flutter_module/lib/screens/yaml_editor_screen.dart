import 'dart:async';
import 'package:flutter/material.dart';
import '../l10n/strings.dart';
import '../models/profile.dart';
import '../services/vpn_channel.dart';
import '../widgets/sora_yaml_editor.dart';

class YamlEditorScreen extends StatefulWidget {
  final ClashProfile profile;
  const YamlEditorScreen({super.key, required this.profile});

  @override
  State<YamlEditorScreen> createState() => _YamlEditorScreenState();
}

class _YamlEditorScreenState extends State<YamlEditorScreen> {
  SoraEditorController? _editor;
  late String _initialText;
  late String _backupText;
  late String _currentText;
  Timer? _validationDebounce;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _initialText = widget.profile.yamlContent;
    _backupText = widget.profile.yamlBackup;
    _currentText = _initialText;
    _validate(_initialText);
  }

  @override
  void dispose() {
    _validationDebounce?.cancel();
    super.dispose();
  }

  void _onTextChanged(String text) {
    _currentText = text;
    _validationDebounce?.cancel();
    _validationDebounce = Timer(const Duration(milliseconds: 300), () {
      _validate(text);
    });
    if (mounted) setState(() {}); // refresh save/revert button enabled state
  }

  /// Check the config with meow-rs (the engine parses/validates it — the Dart
  /// side never parses YAML). `null`/empty means valid; anything else is the
  /// engine's error message.
  Future<void> _validate(String text) async {
    String? error;
    try {
      error = await VpnChannel.instance.validateConfig(text);
    } catch (e) {
      error = e.toString();
    }
    // Ignore stale results if the user kept typing or left the screen.
    if (!mounted || text != _currentText) return;
    setState(() {
      _errorMessage = (error == null || error.isEmpty) ? null : error;
    });
  }

  bool get _hasUnsaved => _currentText != _initialText;
  bool get _isValid => _errorMessage == null;
  bool get _canRevert =>
      _backupText.isNotEmpty && _currentText != _backupText;

  Future<bool> _confirmDiscard() async {
    if (!_hasUnsaved) return true;
    final s = S.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.discardChanges),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(s.discard),
          ),
        ],
      ),
    );
    return ok == true;
  }

  Future<void> _save() async {
    if (!_isValid || _editor == null) return;
    final s = S.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    try {
      // Pull the current text from the native editor (authoritative source).
      final text = await _editor!.getText();
      await VpnChannel.instance.updateProfileYaml(widget.profile.id, text);
      messenger.showSnackBar(SnackBar(content: Text(s.yamlSaved)));
      navigator.pop(true);
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  Future<void> _revert() async {
    if (_editor == null) return;
    final s = S.of(context);
    final revertedLabel = s.yamlReverted;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.revertConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(s.revert),
          ),
        ],
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      final reverted =
          await VpnChannel.instance.revertProfileYaml(widget.profile.id);
      await _editor!.setText(reverted);
      _initialText = reverted;
      _currentText = reverted;
      _validate(reverted);
      if (mounted) setState(() {});
      messenger.showSnackBar(SnackBar(content: Text(revertedLabel)));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text('$e')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop) return;
        final navigator = Navigator.of(context);
        if (await _confirmDiscard()) {
          if (mounted) navigator.pop(false);
        }
      },
      child: Scaffold(
        appBar: AppBar(
          title: Text(widget.profile.name),
          actions: [
            IconButton(
              icon: const Icon(Icons.restore),
              tooltip: s.revert,
              onPressed: _canRevert ? _revert : null,
            ),
            IconButton(
              icon: const Icon(Icons.save),
              tooltip: s.save,
              onPressed: _isValid && _hasUnsaved ? _save : null,
            ),
          ],
        ),
        body: Column(
          children: [
            Expanded(
              child: SoraYamlEditor(
                initialText: _initialText,
                onCreated: (controller) => _editor = controller,
                onChanged: _onTextChanged,
              ),
            ),
            _StatusBar(
              valid: _isValid,
              message: _isValid
                  ? s.yamlValid
                  : s.configInvalid(_errorMessage ?? ''),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusBar extends StatelessWidget {
  final bool valid;
  final String message;
  const _StatusBar({required this.valid, required this.message});

  @override
  Widget build(BuildContext context) {
    final color = valid ? Colors.greenAccent : Colors.redAccent;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: color.withAlpha(20),
        border: Border(top: BorderSide(color: color.withAlpha(80))),
      ),
      child: Row(
        children: [
          Icon(
            valid ? Icons.check_circle : Icons.error,
            size: 16,
            color: color,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(fontSize: 12, color: color),
            ),
          ),
        ],
      ),
    );
  }
}
