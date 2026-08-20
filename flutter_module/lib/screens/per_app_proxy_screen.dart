import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../l10n/strings.dart';
import '../services/vpn_channel.dart';

class PerAppProxyScreen extends StatefulWidget {
  const PerAppProxyScreen({super.key});

  @override
  State<PerAppProxyScreen> createState() => _PerAppProxyScreenState();
}

class _PerAppProxyScreenState extends State<PerAppProxyScreen> {
  String _mode = 'proxy';
  final Set<String> _selectedPackages = {};
  List<Map<String, dynamic>> _apps = [];
  final Map<String, Uint8List?> _iconCache = {};
  String _searchQuery = '';
  bool _showSystemApps = false;
  bool _loading = true;
  bool _scanning = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final config = await VpnChannel.instance.getPerAppConfig();
    final apps = await VpnChannel.instance.getInstalledApps();
    final packagesJson = config['packages'] as String? ?? '[]';
    final packages = (json.decode(packagesJson) as List).cast<String>();
    if (mounted) {
      setState(() {
        _mode = config['mode'] as String? ?? 'proxy';
        _selectedPackages.addAll(packages);
        _apps = apps;
        _loading = false;
      });
    }
  }

  Future<void> _save() async {
    await VpnChannel.instance.setPerAppConfig(_mode, _selectedPackages.toList());
    if (mounted) Navigator.pop(context);
  }

  /// Port of sing-box Android's "扫描中国应用": matches the screen's current
  /// filtered app list by package/component name prefixes and auto-selects
  /// the found ones.
  Future<void> _scanChinaApps() async {
    if (_scanning) return;
    final messenger = ScaffoldMessenger.of(context);
    final s = S.of(context);
    // Snapshot the currently visible (filtered) list; sing-box scans only
    // the current filter view too.
    final visiblePackages =
        _filteredApps.map((a) => a['packageName'] as String).toList();
    setState(() => _scanning = true);
    messenger.showSnackBar(
      SnackBar(
        content: Text(s.perAppScanning),
        duration: const Duration(seconds: 10),
      ),
    );
    try {
      final matched = await VpnChannel.instance.scanChinaApps(visiblePackages);
      if (!mounted) return;
      final before = _selectedPackages.length;
      setState(() => _selectedPackages.addAll(matched));
      final added = _selectedPackages.length - before;
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            matched.isEmpty
                ? s.perAppNoChinaApps
                : added == 0
                    ? s.perAppChinaAlreadySelected
                    : s.perAppChinaSelected(added),
          ),
        ),
      );
    } catch (_) {
      if (!mounted) return;
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(SnackBar(content: Text(s.perAppScanFailed)));
    } finally {
      if (mounted) setState(() => _scanning = false);
    }
  }

  List<Map<String, dynamic>> get _filteredApps {
    return _apps.where((app) {
      if (!_showSystemApps && app['isSystem'] == true) return false;
      if (_searchQuery.isEmpty) return true;
      final query = _searchQuery.toLowerCase();
      final name = (app['appName'] as String).toLowerCase();
      final pkg = (app['packageName'] as String).toLowerCase();
      return name.contains(query) || pkg.contains(query);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final filtered = _filteredApps;

    return Scaffold(
      appBar: AppBar(
        title: Text(s.perAppProxy),
        actions: [
          PopupMenuButton<String>(
            onSelected: (value) {
              if (value == 'scanChina') {
                _scanChinaApps();
                return;
              }
              setState(() {
                if (value == 'showSystem') {
                  _showSystemApps = !_showSystemApps;
                } else if (value == 'selectAll') {
                  _selectedPackages.addAll(filtered.map((a) => a['packageName'] as String));
                } else {
                  _selectedPackages.removeAll(filtered.map((a) => a['packageName'] as String));
                }
              });
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'showSystem',
                child: Text(_showSystemApps ? s.perAppHideSystem : s.perAppShowSystem),
              ),
              PopupMenuItem(value: 'selectAll', child: Text(s.perAppSelectAll)),
              PopupMenuItem(value: 'deselectAll', child: Text(s.perAppDeselectAll)),
              PopupMenuItem(
                value: 'scanChina',
                enabled: !_scanning,
                child: Text(s.perAppScanChina),
              ),
            ],
          ),
          IconButton(
            icon: const Icon(Icons.check),
            onPressed: _save,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: SegmentedButton<String>(
                    segments: [
                      ButtonSegment(value: 'proxy', label: Text(s.perAppModeProxy)),
                      ButtonSegment(value: 'bypass', label: Text(s.perAppModeBypass)),
                    ],
                    selected: {_mode},
                    onSelectionChanged: (sel) => setState(() => _mode = sel.first),
                  ),
                ),
                if (_selectedPackages.isEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: Text(
                      s.perAppDisabledHint,
                      style: TextStyle(color: Theme.of(context).colorScheme.outline, fontSize: 13),
                    ),
                  ),
                if (_selectedPackages.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: Text(
                      '${s.perAppSelected(_selectedPackages.length)} · ${s.perAppRestartRequired}',
                      style: TextStyle(color: Theme.of(context).colorScheme.outline, fontSize: 13),
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                  child: TextField(
                    decoration: InputDecoration(
                      hintText: s.perAppSearch,
                      prefixIcon: const Icon(Icons.search),
                      isDense: true,
                      border: const OutlineInputBorder(),
                    ),
                    onChanged: (v) => setState(() => _searchQuery = v),
                  ),
                ),
                const SizedBox(height: 8),
                Expanded(
                  child: ListView.builder(
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final app = filtered[index];
                      final pkg = app['packageName'] as String;
                      final name = app['appName'] as String;
                      final selected = _selectedPackages.contains(pkg);
                      return _AppTile(
                        packageName: pkg,
                        appName: name,
                        selected: selected,
                        iconCache: _iconCache,
                        onChanged: (v) {
                          setState(() {
                            if (v) {
                              _selectedPackages.add(pkg);
                            } else {
                              _selectedPackages.remove(pkg);
                            }
                          });
                        },
                      );
                    },
                  ),
                ),
              ],
            ),
    );
  }
}

class _AppTile extends StatefulWidget {
  final String packageName;
  final String appName;
  final bool selected;
  final Map<String, Uint8List?> iconCache;
  final ValueChanged<bool> onChanged;

  const _AppTile({
    required this.packageName,
    required this.appName,
    required this.selected,
    required this.iconCache,
    required this.onChanged,
  });

  @override
  State<_AppTile> createState() => _AppTileState();
}

class _AppTileState extends State<_AppTile> {
  Future<Uint8List?>? _iconFuture;

  @override
  void initState() {
    super.initState();
    _loadIcon();
  }

  @override
  void didUpdateWidget(_AppTile old) {
    super.didUpdateWidget(old);
    if (old.packageName != widget.packageName) _loadIcon();
  }

  void _loadIcon() {
    final cached = widget.iconCache[widget.packageName];
    if (cached != null || widget.iconCache.containsKey(widget.packageName)) {
      _iconFuture = Future.value(cached);
    } else {
      _iconFuture = VpnChannel.instance.getAppIcon(widget.packageName).then((bytes) {
        widget.iconCache[widget.packageName] = bytes;
        return bytes;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Uint8List?>(
      future: _iconFuture,
      builder: (context, snap) {
        final icon = snap.data;
        return ListTile(
          onTap: () => widget.onChanged(!widget.selected),
          contentPadding: const EdgeInsets.symmetric(horizontal: 16),
          leading: SizedBox(
            width: 40,
            height: 40,
            child: icon != null
                ? Image.memory(icon, width: 40, height: 40)
                : const Icon(Icons.android, size: 40),
          ),
          title: Text(widget.appName, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(widget.packageName, maxLines: 1, overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12)),
          trailing: Checkbox(
            value: widget.selected,
            onChanged: (v) => widget.onChanged(v ?? false),
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
            visualDensity: VisualDensity.compact,
          ),
        );
      },
    );
  }
}
