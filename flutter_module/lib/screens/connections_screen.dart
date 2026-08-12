import 'dart:async';
import 'package:flutter/material.dart';
import '../l10n/strings.dart';
import '../models/connection.dart';
import '../services/mihomo_api.dart';

class ConnectionsScreen extends StatefulWidget {
  final Future<ConnectionsSnapshot> Function()? getConnectionsOverride;
  final Future<void> Function(String id)? closeConnectionOverride;
  final Future<void> Function()? closeAllConnectionsOverride;

  const ConnectionsScreen({
    super.key,
    this.getConnectionsOverride,
    this.closeConnectionOverride,
    this.closeAllConnectionsOverride,
  });

  @override
  State<ConnectionsScreen> createState() => _ConnectionsScreenState();
}

class _ConnectionsScreenState extends State<ConnectionsScreen>
    with WidgetsBindingObserver {
  List<Connection> _connections = [];
  String _filter = '';
  final Set<String> _dismissedIds = {};
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _poll();
    _startPolling();
  }

  @override
  void dispose() {
    _timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _poll();
      _startPolling();
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      _timer?.cancel();
      _timer = null;
    }
  }

  void _startPolling() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _poll());
  }

  Future<void> _poll() async {
    try {
      final getConns =
          widget.getConnectionsOverride ?? MihomoApi.instance.getConnections;
      final snapshot = await getConns();
      if (mounted) {
        setState(() {
          final serverIds = snapshot.connections.map((c) => c.id).toSet();
          _dismissedIds.removeWhere((id) => !serverIds.contains(id));
          _connections = snapshot.connections
              .where((c) => !_dismissedIds.contains(c.id))
              .toList();
        });
      }
    } catch (_) {}
  }

  Future<void> _closeConnection(String id) async {
    try {
      final close =
          widget.closeConnectionOverride ?? MihomoApi.instance.closeConnection;
      await close(id);
      setState(() {
        _dismissedIds.add(id);
        _connections.removeWhere((c) => c.id == id);
      });
    } catch (_) {}
  }

  Future<void> _closeAll() async {
    final s = S.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: Text(s.closeAllConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(s.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(s.closeAll),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      final closeAll =
          widget.closeAllConnectionsOverride ??
          MihomoApi.instance.closeAllConnections;
      await closeAll();
      if (mounted) setState(() => _connections = []);
    } catch (_) {}
  }

  List<Connection> get _filtered {
    if (_filter.isEmpty) return _connections;
    final q = _filter.toLowerCase();
    return _connections.where((c) {
      final displayHost = c.metadata.host.isNotEmpty
          ? c.metadata.host
          : c.metadata.destinationIP;
      return displayHost.toLowerCase().contains(q);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final items = _filtered;

    return Scaffold(
      appBar: AppBar(
        title: Text(s.connections),
        actions: [
          if (_connections.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete_sweep_outlined),
              tooltip: s.closeAll,
              onPressed: _closeAll,
            ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              decoration: InputDecoration(
                hintText: s.filterConnections,
                prefixIcon: const Icon(Icons.search, size: 20),
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 8),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              onChanged: (v) => setState(() => _filter = v),
            ),
          ),
        ),
      ),
      body: items.isEmpty
          ? Center(
              child: Text(
                s.noConnections,
                style: const TextStyle(color: Colors.white38),
              ),
            )
          : ListView.builder(
              itemCount: items.length,
              itemBuilder: (context, index) {
                final conn = items[index];
                return Dismissible(
                  key: ValueKey(conn.id),
                  direction: DismissDirection.endToStart,
                  background: Container(
                    color: Colors.redAccent,
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.only(right: 16),
                    child: const Icon(Icons.close, color: Colors.white),
                  ),
                  onDismissed: (_) => _closeConnection(conn.id),
                  child: _ConnectionTile(conn: conn),
                );
              },
            ),
    );
  }
}

class _ConnectionTile extends StatelessWidget {
  final Connection conn;

  const _ConnectionTile({required this.conn});

  static String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) {
      return '${(bytes / 1024).toStringAsFixed(1)} KB';
    }
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
  }

  static String _formatDuration(String isoStart) {
    try {
      final elapsed = DateTime.now().difference(DateTime.parse(isoStart));
      if (elapsed.inSeconds < 60) return '${elapsed.inSeconds}s';
      if (elapsed.inMinutes < 60) {
        return '${elapsed.inMinutes}m ${elapsed.inSeconds % 60}s';
      }
      return '${elapsed.inHours}h ${elapsed.inMinutes % 60}m';
    } catch (_) {
      return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = conn.metadata;
    final host = m.host.isNotEmpty ? m.host : m.destinationIP;
    final hostPort = m.destinationPort.isNotEmpty
        ? '$host:${m.destinationPort}'
        : host;
    final duration = _formatDuration(conn.start);
    final chains = conn.chains;

    return ListTile(
      title: Text(
        hostPort,
        style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (chains.isNotEmpty)
            Semantics(
              label: chains.join(' → '),
              child: RichText(
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                text: TextSpan(
                  style: const TextStyle(fontSize: 11, color: Colors.white54),
                  children: [
                    for (int i = 0; i < chains.length; i++) ...[
                      if (i > 0) const TextSpan(text: ' → '),
                      TextSpan(
                        text: chains[i],
                        style: i == chains.length - 1
                            ? const TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.white70,
                              )
                            : null,
                      ),
                    ],
                  ],
                ),
              ),
            ),
          if (conn.rule.isNotEmpty)
            Text(
              conn.rulePayload.isNotEmpty
                  ? '${conn.rule}: ${conn.rulePayload}'
                  : conn.rule,
              style: const TextStyle(fontSize: 11, color: Colors.white38),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
        ],
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(
            '↑ ${_formatBytes(conn.upload)}',
            style: const TextStyle(fontSize: 10, color: Colors.blueAccent),
          ),
          Text(
            '↓ ${_formatBytes(conn.download)}',
            style: const TextStyle(fontSize: 10, color: Colors.greenAccent),
          ),
          if (duration.isNotEmpty)
            Text(
              duration,
              style: const TextStyle(fontSize: 10, color: Colors.white38),
            ),
        ],
      ),
      dense: true,
    );
  }
}
