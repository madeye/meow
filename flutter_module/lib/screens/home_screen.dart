import 'dart:async';
import 'package:flutter/material.dart';
import '../app.dart' show profileChanged;
import '../l10n/strings.dart';
import '../services/vpn_channel.dart';
import '../models/vpn_state.dart';
import '../models/traffic_stats.dart';
import '../models/profile.dart';
import '../models/proxy.dart';
import '../models/proxy_group.dart';
import '../services/mihomo_api.dart';
import '../theme/app_theme.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final _vpn = VpnChannel.instance;
  VpnState _state = VpnState.stopped;
  TrafficStats _traffic = const TrafficStats();
  ClashProfile? _profile;
  // Proxy groups + their members, fetched live from the engine's REST API
  // (`/proxies`). No YAML is parsed in Dart — the engine is the source of
  // truth for what groups exist and which node each one currently selects.
  List<ProxyGroup> _groups = [];
  Map<String, Proxy> _proxies = {};
  String? _expandedGroup;
  StreamSubscription? _stateSub;
  StreamSubscription? _trafficSub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadState();
    profileChanged.addListener(_loadState);
    _stateSub = _vpn.stateStream.listen((s) {
      final wasConnected = _state == VpnState.connected;
      if (mounted) setState(() => _state = s);
      // Once the engine is up, (re)load proxy groups from its REST API.
      if (!wasConnected && s == VpnState.connected) {
        _loadState();
      }
    });
    _trafficSub = _vpn.trafficStream.listen((t) {
      if (mounted) setState(() => _traffic = t);
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // The :vpn process may have been killed while we were backgrounded.
      // Re-query rather than trusting the cached state.
      _loadState();
    }
  }

  Future<void> _loadState() async {
    try {
      final state = await _vpn.getState();
      final profile = await _vpn.getSelectedProfile();
      // Proxy groups come only from the live engine. When the VPN is off the
      // engine isn't running, so there are no groups to show — selection is a
      // runtime operation against the REST API, not a config-parsing one.
      var groups = <ProxyGroup>[];
      var proxies = <String, Proxy>{};
      if (state == VpnState.connected) {
        try {
          final result = await MihomoApi.instance.getProxies();
          groups = result.selectableGroups;
          proxies = result.proxies;
        } catch (_) {
          // Engine API not reachable yet — leave the list empty.
        }
      }
      if (mounted) {
        setState(() {
          _state = state;
          _profile = profile;
          _groups = groups;
          _proxies = proxies;
        });
      }
    } catch (_) {}
  }

  /// Select [node] within [group] via the engine REST API, then reflect the
  /// new `now` locally. The engine owns selection state — the app never edits
  /// the config to change the active node.
  Future<void> _selectNode(String group, String node) async {
    final idx = _groups.indexWhere((g) => g.name == group);
    if (idx < 0) return;
    try {
      await MihomoApi.instance.selectProxy(group, node);
      if (!mounted) return;
      final g = _groups[idx];
      setState(() {
        _groups[idx] = ProxyGroup(
          name: g.name,
          type: g.type,
          now: node,
          all: g.all,
          history: g.history,
        );
      });
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  /// Run a latency probe across every member of [group] and refresh delays.
  Future<void> _testGroup(String group) async {
    try {
      await MihomoApi.instance.testGroupDelay(group);
    } catch (_) {
      // Ignore probe failures — surfaced as "--" in the UI.
    }
    await _loadState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    profileChanged.removeListener(_loadState);
    _stateSub?.cancel();
    _trafficSub?.cancel();
    super.dispose();
  }

  bool _toggling = false;

  Future<void> _toggle(bool value) async {
    if (_toggling) return;
    setState(() => _toggling = true);
    try {
      if (value) {
        await _vpn.connect();
      } else {
        await _vpn.disconnect();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('$e')));
      }
    }
    // Reset after state stream delivers the transitioning state,
    // or after a short delay as fallback.
    Future.delayed(const Duration(milliseconds: 500), () {
      if (mounted) setState(() => _toggling = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final isOn = _state == VpnState.connected;
    final isTransitioning =
        _state == VpnState.connecting || _state == VpnState.stopping;

    return Scaffold(
      body: CustomScrollView(
        slivers: [
          // App bar with switch
          SliverAppBar(
            pinned: true,
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(s.appName),
                if (_profile != null && isOn)
                  Text(
                    _profile!.name,
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontWeight: FontWeight.normal,
                    ),
                  ),
              ],
            ),
            actions: [
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 300),
                child: isTransitioning
                    ? const Padding(
                        key: ValueKey('spinner'),
                        padding: EdgeInsets.only(right: 16),
                        child: SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        ),
                      )
                    : Switch(
                        key: const ValueKey('switch'),
                        value: isOn,
                        onChanged: _state.canToggle && !_toggling
                            ? _toggle
                            : null,
                      ),
              ),
            ],
          ),

          // Status card
          SliverToBoxAdapter(child: _buildStatusCard(isOn)),

          // Traffic row
          if (isOn)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 8,
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: _TrafficTile(
                        icon: Icons.arrow_upward,
                        label: s.upload,
                        rate: _traffic.txRateStr,
                        total: _traffic.txTotalStr,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: _TrafficTile(
                        icon: Icons.arrow_downward,
                        label: s.download,
                        rate: _traffic.rxRateStr,
                        total: _traffic.rxTotalStr,
                      ),
                    ),
                  ],
                ),
              ),
            ),

          // Section header
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
              child: Row(
                children: [
                  Text(
                    s.proxyGroups,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.w600,
                      fontSize: 13,
                    ),
                  ),
                  const Spacer(),
                  if (_profile != null)
                    Text(
                      _profile!.name,
                      style: TextStyle(
                        fontSize: 12,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
          ),

          // Proxy groups (live from the engine REST API)
          if (_groups.isEmpty)
            SliverFillRemaining(
              hasScrollBody: false,
              child: Center(
                child: Text(
                  isOn ? s.noGroups : s.noSubscriptionHint,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            )
          else
            SliverList(
              delegate: SliverChildBuilderDelegate((context, index) {
                final group = _groups[index];
                return _ProxyGroupCard(
                  group: group,
                  proxies: _proxies,
                  expanded: _expandedGroup == group.name,
                  onToggleExpand: () => setState(() {
                    _expandedGroup =
                        _expandedGroup == group.name ? null : group.name;
                  }),
                  onSelect: (node) => _selectNode(group.name, node),
                  onTest: () => _testGroup(group.name),
                );
              }, childCount: _groups.length),
            ),

          // Bottom padding
          const SliverPadding(padding: EdgeInsets.only(bottom: 16)),
        ],
      ),
    );
  }

  Widget _buildStatusCard(bool isOn) {
    final s = S.of(context);
    final theme = Theme.of(context);
    final meow = theme.extension<MeowColors>()!;
    String stateLabel(VpnState state) {
      switch (state) {
        case VpnState.idle:
          return s.notConnected;
        case VpnState.connecting:
          return s.connecting;
        case VpnState.connected:
          return s.connected;
        case VpnState.stopping:
          return s.disconnecting;
        case VpnState.stopped:
          return s.disconnected;
      }
    }

    final color = isOn ? meow.connected : theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: color.withAlpha(30),
                  border: Border.all(color: color, width: 2),
                ),
                child: Icon(
                  isOn ? Icons.vpn_key : Icons.vpn_key_off,
                  color: color,
                  size: 24,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      stateLabel(_state),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: color,
                      ),
                    ),
                    if (_profile != null)
                      Text(
                        _profile!.name,
                        style: TextStyle(
                          fontSize: 13,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TrafficTile extends StatelessWidget {
  final IconData icon;
  final String label;
  final String rate;
  final String total;

  const _TrafficTile({
    required this.icon,
    required this.label,
    required this.rate,
    required this.total,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(icon, size: 20, color: cs.primary),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  rate,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                Text(
                  '$total $label',
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// One proxy group, rendered as an expandable card. Collapsed it shows the
/// group name, type and the member it currently points at (`now`); expanded it
/// lists every member with its latest latency and lets the user pick one.
class _ProxyGroupCard extends StatelessWidget {
  final ProxyGroup group;
  final Map<String, Proxy> proxies;
  final bool expanded;
  final VoidCallback onToggleExpand;
  final ValueChanged<String> onSelect;
  final VoidCallback onTest;

  const _ProxyGroupCard({
    required this.group,
    required this.proxies,
    required this.expanded,
    required this.onToggleExpand,
    required this.onSelect,
    required this.onTest,
  });

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      child: Card(
        child: Column(
          children: [
            ListTile(
              leading: Icon(Icons.lan_outlined, color: cs.primary, size: 22),
              title: Text(
                group.name,
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w600),
              ),
              subtitle: Text(
                '${group.type} · ${group.now}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
              ),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.speed, size: 20),
                    tooltip: s.urlTestAll,
                    onPressed: onTest,
                  ),
                  Icon(expanded ? Icons.expand_less : Icons.expand_more),
                ],
              ),
              onTap: onToggleExpand,
            ),
            if (expanded)
              ...group.all.map((name) {
                final selected = name == group.now;
                final delay = proxies[name]?.latestDelay ?? 0;
                return ListTile(
                  dense: true,
                  contentPadding:
                      const EdgeInsets.only(left: 28, right: 16),
                  leading: Icon(
                    selected ? Icons.check_circle : Icons.circle_outlined,
                    size: 20,
                    color: selected
                        ? cs.primary
                        : cs.onSurfaceVariant.withValues(alpha: 0.5),
                  ),
                  title: Text(
                    name,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight:
                          selected ? FontWeight.w600 : FontWeight.normal,
                    ),
                  ),
                  trailing: Text(
                    delay > 0 ? s.latencyMs(delay) : s.untested,
                    style: TextStyle(
                      fontSize: 11,
                      color: delay > 0 ? cs.primary : cs.onSurfaceVariant,
                    ),
                  ),
                  onTap: selected ? null : () => onSelect(name),
                );
              }),
          ],
        ),
      ),
    );
  }
}
