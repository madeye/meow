import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../l10n/strings.dart';

class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key});

  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends State<LogsScreen> {
  static const _method = MethodChannel('io.github.madeye.meow/vpn');
  final List<String> _logs = [];
  final _scrollController = ScrollController();
  Timer? _timer;
  bool _autoScroll = true;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _fetchLogs());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _fetchLogs() async {
    try {
      final logs = await _method.invokeMethod<List>('getLogs');
      if (logs != null && mounted) {
        setState(() {
          for (final log in logs) {
            _logs.add(log as String);
          }
          // Keep last 500 lines
          if (_logs.length > 500) {
            _logs.removeRange(0, _logs.length - 500);
          }
        });
        if (_autoScroll && _scrollController.hasClients) {
          _scrollController.jumpTo(_scrollController.position.maxScrollExtent);
        }
      }
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(s.logs),
        actions: [
          IconButton(
            icon: Icon(_autoScroll ? Icons.vertical_align_bottom : Icons.pause),
            onPressed: () => setState(() => _autoScroll = !_autoScroll),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed: () => setState(() => _logs.clear()),
          ),
        ],
      ),
      body: _logs.isEmpty
          ? Center(
              child: Text(s.noLogs, style: const TextStyle(color: Colors.white38)),
            )
          : ListView.builder(
              controller: _scrollController,
              itemCount: _logs.length,
              itemBuilder: (_, i) {
                final log = _logs[i];
                final color = log.contains('ERROR')
                    ? Colors.redAccent
                    : log.contains('WARN')
                        ? Colors.orangeAccent
                        : Colors.white60;
                return Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 1),
                  child: Text(
                    log,
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 11,
                      color: color,
                    ),
                  ),
                );
              },
            ),
    );
  }
}
