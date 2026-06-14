import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../app.dart' show notifyProfileChanged;
import '../l10n/strings.dart';
import '../services/vpn_channel.dart';
import '../models/profile.dart';
import 'yaml_editor_screen.dart';

class SubscriptionsScreen extends StatefulWidget {
  const SubscriptionsScreen({super.key});

  @override
  State<SubscriptionsScreen> createState() => _SubscriptionsScreenState();
}

class _SubscriptionsScreenState extends State<SubscriptionsScreen> {
  final _vpn = VpnChannel.instance;
  List<ClashProfile> _profiles = [];
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool notify = false}) async {
    setState(() => _loading = true);
    try {
      _profiles = await _vpn.getProfiles();
    } catch (_) {}
    if (mounted) setState(() => _loading = false);
    if (notify) notifyProfileChanged();
  }

  Future<T> _runWithProgress<T>(Future<T> Function() task) async {
    if (!mounted) return task();
    showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );
    try {
      return await task();
    } finally {
      if (mounted) Navigator.of(context, rootNavigator: true).pop();
    }
  }

  Future<void> _addSubscription() async {
    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (_) => const _SubscriptionDialog(),
    );
    if (result != null) {
      try {
        await _runWithProgress(
            () => _vpn.addSubscription(result['name']!, result['url']!));
        await _load(notify: true);
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Error: $e')),
          );
        }
      }
    }
  }

  Future<void> _editSubscription(ClashProfile profile) async {
    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (_) => _SubscriptionDialog(name: profile.name, url: profile.url),
    );
    if (result != null) {
      try {
        await _vpn.updateSubscription(profile.id, result['name']!, result['url']!);
        await _load(notify: true);
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Error: $e')),
          );
        }
      }
    }
  }

  Future<void> _deleteSubscription(ClashProfile profile) async {
    final s = S.of(context);
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(s.deleteSubscription),
        content: Text(s.deleteConfirm(profile.name)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(s.cancel)),
          TextButton(onPressed: () => Navigator.pop(ctx, true), child: Text(s.delete)),
        ],
      ),
    );
    if (confirm == true) {
      await _vpn.deleteSubscription(profile.id);
      await _load(notify: true);
    }
  }

  Future<void> _refreshSubscription(ClashProfile profile) async {
    try {
      await _runWithProgress(() => _vpn.refreshSubscription(profile.id));
      await _load(notify: true);
      if (mounted) {
        final s = S.of(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(s.updated(profile.name))),
        );
      }
    } catch (e) {
      if (mounted) {
        final s = S.of(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(s.refreshFailed(e.toString()))),
        );
      }
    }
  }

  Future<void> _selectProfile(ClashProfile profile) async {
    await _vpn.selectProfile(profile.id);
    await _load(notify: true);
  }

  Future<void> _editYaml(ClashProfile profile) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => YamlEditorScreen(profile: profile)),
    );
    if (saved == true) await _load(notify: true);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(s.subscriptions),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () async {
              try {
                await _runWithProgress(() => _vpn.refreshAll());
              } catch (_) {}
              await _load(notify: true);
            },
          ),
          IconButton(icon: const Icon(Icons.add), onPressed: _addSubscription),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _profiles.isEmpty
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.cloud_off,
                          size: 64,
                          color: Theme.of(context)
                              .colorScheme
                              .onSurfaceVariant
                              .withValues(alpha: 0.5)),
                      const SizedBox(height: 16),
                      Text(s.noSubscriptions,
                          style: TextStyle(
                              color: Theme.of(context)
                                  .colorScheme
                                  .onSurfaceVariant)),
                      const SizedBox(height: 16),
                      FilledButton.icon(
                        onPressed: _addSubscription,
                        icon: const Icon(Icons.add),
                        label: Text(s.addSubscription),
                      ),
                    ],
                  ),
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.builder(
                    itemCount: _profiles.length,
                    itemBuilder: (_, i) => _ProfileTile(
                      profile: _profiles[i],
                      onSelect: () => _selectProfile(_profiles[i]),
                      onEdit: () => _editSubscription(_profiles[i]),
                      onDelete: () => _deleteSubscription(_profiles[i]),
                      onRefresh: () => _refreshSubscription(_profiles[i]),
                      onEditYaml: () => _editYaml(_profiles[i]),
                    ),
                  ),
                ),
    );
  }
}

class _ProfileTile extends StatefulWidget {
  final ClashProfile profile;
  final VoidCallback onSelect;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onRefresh;
  final VoidCallback onEditYaml;

  const _ProfileTile({
    required this.profile,
    required this.onSelect,
    required this.onEdit,
    required this.onDelete,
    required this.onRefresh,
    required this.onEditYaml,
  });

  @override
  State<_ProfileTile> createState() => _ProfileTileState();
}

class _ProfileTileState extends State<_ProfileTile> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final p = widget.profile;
    final proxyNames = p.proxyNames;
    final updated = p.lastUpdated > 0
        ? DateTime.fromMillisecondsSinceEpoch(p.lastUpdated * 1000)
        : null;
    final cs = Theme.of(context).colorScheme;

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Column(
        children: [
          ListTile(
            leading: Icon(
              p.selected ? Icons.check_circle : Icons.circle_outlined,
              color: p.selected
                  ? cs.primary
                  : cs.onSurfaceVariant.withValues(alpha: 0.5),
            ),
            title: Text(p.name, style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (p.url.isNotEmpty)
                  Text(p.url, maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                if (updated != null)
                  Text('Updated: ${updated.toLocal().toString().substring(0, 16)}',
                      style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
                Text('${proxyNames.length} ${s.proxies}',
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
              ],
            ),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (proxyNames.isNotEmpty)
                  IconButton(
                    icon: Icon(_expanded ? Icons.expand_less : Icons.expand_more),
                    onPressed: () => setState(() => _expanded = !_expanded),
                  ),
                PopupMenuButton<String>(
                  onSelected: (v) {
                    switch (v) {
                      case 'select': widget.onSelect();
                      case 'edit': widget.onEdit();
                      case 'editYaml': widget.onEditYaml();
                      case 'refresh': widget.onRefresh();
                      case 'delete': widget.onDelete();
                    }
                  },
                  itemBuilder: (_) => [
                    if (!p.selected)
                      PopupMenuItem(value: 'select', child: Text(s.select)),
                    PopupMenuItem(value: 'edit', child: Text(s.edit)),
                    if (p.yamlContent.isNotEmpty)
                      PopupMenuItem(value: 'editYaml', child: Text(s.editYaml)),
                    PopupMenuItem(value: 'refresh', child: Text(s.refresh)),
                    PopupMenuItem(value: 'delete', child: Text(s.delete)),
                  ],
                ),
              ],
            ),
            onTap: widget.onSelect,
          ),
          // Proxy nodes list (expanded)
          if (_expanded && proxyNames.isNotEmpty)
            Container(
              padding: const EdgeInsets.only(left: 16, right: 16, bottom: 8),
              child: Column(
                children: proxyNames
                    .map((name) => ListTile(
                          dense: true,
                          leading: const Icon(Icons.vpn_key, size: 18),
                          title: Text(name, style: const TextStyle(fontSize: 14)),
                          visualDensity: VisualDensity.compact,
                        ))
                    .toList(),
              ),
            ),
        ],
      ),
    );
  }
}

class _SubscriptionDialog extends StatefulWidget {
  final String? name;
  final String? url;

  const _SubscriptionDialog({this.name, this.url});

  @override
  State<_SubscriptionDialog> createState() => _SubscriptionDialogState();
}

class _SubscriptionDialogState extends State<_SubscriptionDialog> {
  late final TextEditingController _nameCtrl;
  late final TextEditingController _urlCtrl;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.name ?? '');
    _urlCtrl = TextEditingController(text: widget.url ?? '');
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _urlCtrl.dispose();
    super.dispose();
  }

  Future<void> _pasteUrl() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final text = data?.text?.trim();
    if (!mounted) return;
    if (text == null || text.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(S.of(context).clipboardEmpty)));
      return;
    }
    _urlCtrl.text = text;
    _urlCtrl.selection =
        TextSelection.collapsed(offset: _urlCtrl.text.length);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final isEdit = widget.name != null;
    return AlertDialog(
      title: Text(isEdit ? s.editSubscription : s.addSubscription),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _nameCtrl,
            decoration: InputDecoration(labelText: s.name, hintText: 'My Server'),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _urlCtrl,
            decoration: InputDecoration(
              labelText: s.subscriptionUrl,
              hintText: 'https://...',
              suffixIcon: IconButton(
                icon: const Icon(Icons.content_paste),
                tooltip: s.pasteFromClipboard,
                onPressed: _pasteUrl,
              ),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(s.cancel),
        ),
        FilledButton(
          onPressed: () {
            if (_nameCtrl.text.isEmpty || _urlCtrl.text.isEmpty) return;
            Navigator.pop(context, {'name': _nameCtrl.text, 'url': _urlCtrl.text});
          },
          child: Text(isEdit ? s.save : s.add),
        ),
      ],
    );
  }
}
