import 'dart:async';
import 'package:flutter/material.dart';
import '../app.dart' show profileChanged;
import '../l10n/strings.dart';
import '../services/vpn_channel.dart';
import '../models/vpn_state.dart';
import '../models/traffic_stats.dart';
import '../models/profile.dart';
import '../models/proxy_group.dart';
import '../services/mihomo_api.dart';

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
  List<String> _proxyNames = [];
  String? _selectedProxy;
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
      if (!wasConnected && s == VpnState.connected && _selectedProxy != null && _profile != null) {
        _vpn.selectProxyNode(_selectedProxy!, _profile!.yamlContent);
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
      // Source of truth for the node list:
      //   1) When the engine is running, the live `/proxies` endpoint —
      //      this includes nodes pulled in via `proxy-providers:` URLs that
      //      aren't present in the raw subscription YAML.
      //   2) Otherwise, parse the subscription YAML with the real YAML
      //      parser (`ProxiesResult.fromYaml`); the regex-based
      //      `Profile.proxyNames` only handled inline `proxies:` with a
      //      narrow indent shape and missed many real-world subscriptions.
      List<String> names = const [];
      if (state == VpnState.connected) {
        try {
          final result = await MihomoApi.instance.getProxies();
          names = result.proxies.keys
              .where((n) => n != 'DIRECT' && n != 'REJECT' && n != 'COMPATIBLE')
              .toList();
        } catch (_) {
          // Fall through to YAML if the engine API is unreachable.
        }
      }
      if (names.isEmpty && profile != null) {
        final fromYaml = ProxiesResult.fromYaml(profile.yamlContent);
        names = fromYaml.proxies.keys
            .where((n) => n != 'DIRECT' && n != 'REJECT' && n != 'COMPATIBLE')
            .toList();
      }
      if (mounted) {
        setState(() {
          _state = state;
          final changed = _profile?.id != profile?.id;
          _profile = profile;
          _proxyNames = names;
          if (changed || _selectedProxy == null || !_proxyNames.contains(_selectedProxy)) {
            final saved = profile?.selectedProxy ?? '';
            if (saved.isNotEmpty && _proxyNames.contains(saved)) {
              _selectedProxy = saved;
            } else {
              _selectedProxy = _proxyNames.isNotEmpty ? _proxyNames.first : null;
            }
          }
        });
      }
    } catch (_) {}
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$e')),
        );
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
                if (_selectedProxy != null && isOn)
                  Text(
                    _selectedProxy!,
                    style: const TextStyle(fontSize: 12, color: Colors.white54, fontWeight: FontWeight.normal),
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
                        onChanged: _state.canToggle && !_toggling ? _toggle : null,
                        activeTrackColor: Colors.greenAccent,
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
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
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
                    s.proxyNodes,
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
                      style: const TextStyle(
                          fontSize: 12, color: Colors.white38),
                    ),
                ],
              ),
            ),
          ),

          // Proxy node list
          if (_proxyNames.isEmpty)
            SliverFillRemaining(
              hasScrollBody: false,
              child: Center(
                child: Text(
                  s.noSubscriptionHint,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white38),
                ),
              ),
            )
          else
            SliverList(
              delegate: SliverChildBuilderDelegate(
                (context, index) {
                  final name = _proxyNames[index];
                  final selected = name == _selectedProxy;
                  return _ProxyNodeTile(
                    name: name,
                    selected: selected,
                    onTap: () {
                      setState(() => _selectedProxy = name);
                      if (_profile != null) {
                        _vpn.saveSelectedProxy(_profile!.id, name);
                        _vpn.selectProxyNode(name, _profile!.yamlContent);
                      }
                    },
                  );
                },
                childCount: _proxyNames.length,
              ),
            ),

          // Bottom padding
          const SliverPadding(padding: EdgeInsets.only(bottom: 16)),
        ],
      ),
    );
  }

  Widget _buildStatusCard(bool isOn) {
    final s = S.of(context);
    String stateLabel(VpnState state) {
      switch (state) {
        case VpnState.idle: return s.notConnected;
        case VpnState.connecting: return s.connecting;
        case VpnState.connected: return s.connected;
        case VpnState.stopping: return s.disconnecting;
        case VpnState.stopped: return s.disconnected;
      }
    }
    final color = isOn ? Colors.greenAccent : Colors.grey;
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
                    if (_selectedProxy != null)
                      Text(
                        _selectedProxy!,
                        style: const TextStyle(
                            fontSize: 13, color: Colors.white54),
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
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Icon(icon, size: 20, color: Colors.white54),
            const SizedBox(width: 8),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(rate,
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w600)),
                Text('$total $label',
                    style:
                        const TextStyle(fontSize: 11, color: Colors.white38)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ProxyNodeTile extends StatelessWidget {
  final String name;
  final bool selected;
  final VoidCallback onTap;

  const _ProxyNodeTile({
    required this.name,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      child: Card(
        color: selected
            ? Theme.of(context).colorScheme.primaryContainer.withAlpha(60)
            : null,
        child: ListTile(
          leading: Icon(
            selected ? Icons.check_circle : Icons.circle_outlined,
            color: selected ? Colors.greenAccent : Colors.white24,
            size: 22,
          ),
          title: Text(
            name,
            style: TextStyle(
              fontSize: 14,
              fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
            ),
          ),
          trailing: selected
              ? Text(S.of(context).active,
                  style: const TextStyle(fontSize: 11, color: Colors.greenAccent))
              : null,
          onTap: onTap,
          dense: true,
        ),
      ),
    );
  }
}
