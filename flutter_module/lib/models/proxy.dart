class ProxyHistory {
  final String time;
  final int delay; // ms; 0 = timeout/untested

  const ProxyHistory({required this.time, required this.delay});

  factory ProxyHistory.fromJson(Map<String, dynamic> json) {
    // Handle both string time (meow-go) and struct time (meow-rust)
    String timeStr;
    final rawTime = json['time'];
    if (rawTime is String) {
      timeStr = rawTime;
    } else if (rawTime is Map<String, dynamic>) {
      // Rust SystemTime: {"secs_since_epoch": N, "nanos_since_epoch": N}
      final secs = rawTime['secs_since_epoch'] as int? ?? 0;
      timeStr = DateTime.fromMillisecondsSinceEpoch(secs * 1000).toIso8601String();
    } else {
      timeStr = '';
    }
    return ProxyHistory(
      time: timeStr,
      delay: json['delay'] as int? ?? 0,
    );
  }
}

class Proxy {
  final String name;
  final String type;
  final List<ProxyHistory> history;

  const Proxy({
    required this.name,
    required this.type,
    required this.history,
  });

  factory Proxy.fromJson(String name, Map<String, dynamic> json) => Proxy(
        name: name,
        type: json['type'] as String? ?? '',
        history: (json['history'] as List<dynamic>? ?? [])
            .whereType<Map<String, dynamic>>()
            .map(ProxyHistory.fromJson)
            .toList(),
      );

  /// Delay from the most recent history entry; 0 if none.
  int get latestDelay => history.isNotEmpty ? history.last.delay : 0;
}
