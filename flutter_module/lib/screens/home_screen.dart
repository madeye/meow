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
  // Most recent per-member delay from the last group speed test, keyed by
  // member name. /proxies/{group}/delay does not persist into a sub-group's
  // history, so the display reads this authoritative value instead.
  final Map<String, int> _delays = {};
  final Map<String, Object> _testingGroups = {};
  int _delayGeneration = 0;
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
      if (mounted) {
        setState(() {
          _state = s;
          if (s != VpnState.connected) {
            _delayGeneration++;
            _delays.clear();
            _testingGroups.clear();
          }
        });
      }
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
        // The engine reports Connected as soon as the TUN is up, but its
        // REST controller (127.0.0.1:9090) is bound asynchronously by a
        // spawned task and may not be listening for a few hundred ms. A
        // single getProxies() that races that window would leave the group
        // list empty for the whole session (no further state transition
        // fires a reload while Connected). Retry briefly until the
        // controller is up.
        for (var attempt = 0; attempt < 5; attempt++) {
          try {
            final result = await MihomoApi.instance.getProxies();
            groups = result.selectableGroups;
            proxies = result.proxies;
            break;
          } catch (_) {
            // Controller not bound yet; back off and retry.
            if (attempt < 4) {
              await Future.delayed(const Duration(milliseconds: 500));
            }
          }
        }
      }
      if (mounted) {
        setState(() {
          if (state != VpnState.connected || _profile?.id != profile?.id) {
            _delayGeneration++;
            _delays.clear();
            _testingGroups.clear();
          }
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

  /// Run a latency probe across every member of [members] and refresh delays.
  /// Each member's result is shown as soon as it lands, then a final
  /// [_loadState] reconciles VPN state/profile.
  Future<void> _testGroup(String groupName, List<String> members) async {
    if (_testingGroups.containsKey(groupName)) return;
    final token = Object();
    final generation = _delayGeneration;
    _testingGroups[groupName] = token;
    // Drop stale results for the members being re-tested so the UI shows
    // them as untested until each fresh probe lands.
    setState(() {
      for (final member in members) {
        _delays.remove(member);
      }
    });
    try {
      await MihomoApi.instance.testGroupDelay(
        members,
        onMemberDone: (name, delay) async {
          if (mounted &&
              generation == _delayGeneration &&
              _state == VpnState.connected) {
            setState(() => _delays[name] = delay);
          }
        },
      );
    } catch (_) {
      // Ignore probe failures — surfaced as untested in the UI.
    }
    if (generation == _delayGeneration) {
      await _loadState();
    }
    if (identical(_testingGroups[groupName], token)) {
      if (mounted) {
        setState(() => _testingGroups.remove(groupName));
      } else {
        _testingGroups.remove(groupName);
      }
    }
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
    // Name -> group lookup so a group member that is itself a sub-group can
    // resolve its delay (leaf members live in _proxies, sub-groups here).
    final groupMap = {for (final g in _groups) g.name: g};

    return Scaffold(
      body: CustomScrollView(
        physics: _groups.isEmpty
            ? const NeverScrollableScrollPhysics()
            : const ClampingScrollPhysics(),
        slivers: [
          // App bar with switch
          SliverAppBar(
            pinned: true,
            title: Text(s.appName),
            actions: [
              // The switch stays in place while the VPN connects/disconnects;
              // during the transition it is simply disabled (grayed out) and
              // the loading spinner is shown in the status card below.
              Padding(
                padding: const EdgeInsets.only(right: 4),
                child: Switch(
                  value: isOn,
                  onChanged: _state.canToggle && !_toggling ? _toggle : null,
                ),
              ),
            ],
          ),

          // Status card
          SliverToBoxAdapter(
            child: _buildStatusCard(isOn, loading: isTransitioning),
          ),

          // Traffic row
          if (isOn)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16,
                  vertical: 6,
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
                  delayOf: (name) => _delays[name] ??
                      _proxies[name]?.latestDelay ??
                      groupMap[name]?.latestDelay ??
                      0,
                  expanded: _expandedGroup == group.name,
                  onToggleExpand: () => setState(() {
                    _expandedGroup =
                        _expandedGroup == group.name ? null : group.name;
                  }),
                  onSelect: (node) => _selectNode(group.name, node),
                  testing: _testingGroups.containsKey(group.name),
                  onTest: () => _testGroup(group.name, group.all),
                );
              }, childCount: _groups.length),
            ),

          // Bottom padding
          const SliverPadding(padding: EdgeInsets.only(bottom: 24)),
        ],
      ),
    );
  }

  Widget _buildStatusCard(bool isOn, {bool loading = false}) {
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
    // While the VPN connects/disconnects, highlight the status badge with the
    // brand primary color so the loading spinner is clearly visible.
    final accent = loading ? theme.colorScheme.primary : color;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: accent.withAlpha(30),
                  border: Border.all(color: accent, width: 2),
                ),
                child: loading
                    ? Center(
                        // Center gives the spinner loose constraints so it
                        // renders at its natural 36px inside the 48px circle
                        // instead of being stretched to fill it.
                        child: CircularProgressIndicator(
                          strokeWidth: 3.5,
                          color: accent,
                        ),
                      )
                    : Icon(
                        isOn ? Icons.vpn_key : Icons.vpn_key_off,
                        color: color,
                        size: 24,
                      ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      stateLabel(_state),
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: accent,
                        height: 1.3,
                      ),
                      // Force a fixed strut line height: mixed CJK/ASCII
                      // strings (e.g. "连接中...") can report taller font
                      // metrics than pure-CJK ones at enlarged system font
                      // scales, which would make the card height jump when
                      // the loading state toggles.
                      strutStyle: const StrutStyle(
                        fontSize: 16,
                        height: 1.3,
                        forceStrutHeight: true,
                      ),
                    ),
                    if (_profile != null)
                      Text(
                        _profile!.name,
                        style: TextStyle(
                          fontSize: 13,
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                          height: 1.3,
                        ),
                        strutStyle: const StrutStyle(
                          fontSize: 13,
                          height: 1.3,
                          forceStrutHeight: true,
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
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
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
  // Resolves a member name to its latest delay (ms, 0 = untested). Abstracts
  // over leaf proxies and sub-group members — see the home screen builder.
  final int Function(String) delayOf;
  final bool expanded;
  final bool testing;
  final VoidCallback onToggleExpand;
  final ValueChanged<String> onSelect;
  final VoidCallback onTest;

  const _ProxyGroupCard({
    required this.group,
    required this.delayOf,
    required this.expanded,
    required this.testing,
    required this.onToggleExpand,
    required this.onSelect,
    required this.onTest,
  });

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Card(
        margin: EdgeInsets.zero,
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
                    icon: testing
                        ? const SizedBox.square(
                            dimension: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.speed, size: 20),
                    tooltip: s.urlTestAll,
                    onPressed: testing ? null : onTest,
                  ),
                  Icon(expanded ? Icons.expand_less : Icons.expand_more),
                ],
              ),
              onTap: onToggleExpand,
            ),
            if (expanded)
              ...group.all.map((name) {
                final selected = name == group.now;
                final delay = delayOf(name);
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
