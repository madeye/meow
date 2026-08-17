/// Traffic data from the meow external-controller WebSocket stream (/traffic).
/// Distinct from [TrafficStats] which comes from the Kotlin EventChannel.
class MeowTraffic {
  final int up;    // bytes per second upload
  final int down;  // bytes per second download

  const MeowTraffic({required this.up, required this.down});

  factory MeowTraffic.fromJson(Map<String, dynamic> json) => MeowTraffic(
        up: json['up'] as int? ?? 0,
        down: json['down'] as int? ?? 0,
      );
}
