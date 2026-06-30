import 'proxy.dart';

/// Group types as reported by mihomo's /proxies endpoint.
const _kGroupTypes = {
  'Selector',
  'URLTest',
  'Fallback',
  'LoadBalance',
  'Relay',
};

class ProxyGroup {
  final String name;
  final String type; // Selector | URLTest | Fallback | LoadBalance | Relay
  final String now;  // currently selected member name
  final List<String> all;
  final List<ProxyHistory> history;

  const ProxyGroup({
    required this.name,
    required this.type,
    required this.now,
    required this.all,
    required this.history,
  });

  factory ProxyGroup.fromJson(String name, Map<String, dynamic> json) =>
      ProxyGroup(
        name: name,
        type: json['type'] as String? ?? '',
        now: json['now'] as String? ?? '',
        all: (json['all'] as List<dynamic>? ?? []).cast<String>(),
        history: (json['history'] as List<dynamic>? ?? [])
            .map((e) => ProxyHistory.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Result of GET /proxies, split into proxy groups and leaf proxies.
class ProxiesResult {
  final Map<String, ProxyGroup> groups;
  final Map<String, Proxy> proxies;

  const ProxiesResult({required this.groups, required this.proxies});

  /// Parse the raw /proxies JSON response body.
  factory ProxiesResult.parse(Map<String, dynamic> json) {
    final raw = (json['proxies'] as Map<String, dynamic>? ?? {});
    final groups = <String, ProxyGroup>{};
    final proxies = <String, Proxy>{};
    for (final entry in raw.entries) {
      if (entry.value is! Map<String, dynamic>) continue;
      final data = entry.value as Map<String, dynamic>;
      final type = data['type'] as String? ?? '';
      if (_kGroupTypes.contains(type)) {
        groups[entry.key] = ProxyGroup.fromJson(entry.key, data);
      } else {
        proxies[entry.key] = Proxy.fromJson(entry.key, data);
      }
    }
    return ProxiesResult(groups: groups, proxies: proxies);
  }

  /// The user-selectable proxy groups, sorted by name. Mirrors meow-ios's
  /// `ProxyGroupModel.build`: hides the top-level `GLOBAL` aggregator (it's
  /// not a user-facing selector) and orders groups deterministically — the
  /// `/proxies` map order is non-deterministic (Go/Rust map iteration).
  List<ProxyGroup> get selectableGroups {
    final list = groups.values
        .where((g) => g.name != 'GLOBAL' && _kGroupTypes.contains(g.type))
        .toList();
    list.sort(
      (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()),
    );
    return list;
  }
}
