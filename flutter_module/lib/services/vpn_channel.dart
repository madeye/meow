import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';
import '../models/profile.dart';
import '../models/vpn_state.dart';
import '../models/traffic_stats.dart';

class VpnChannel {
  static const _method = MethodChannel('io.github.madeye.meow/vpn');
  static const _stateEvent = EventChannel('io.github.madeye.meow/vpn_state');
  static const _trafficEvent = EventChannel('io.github.madeye.meow/traffic');

  static VpnChannel? _instance;
  static VpnChannel get instance => _instance ??= VpnChannel._();
  VpnChannel._();

  Stream<VpnState>? _stateStream;
  Stream<TrafficStats>? _trafficStream;

  Stream<VpnState> get stateStream {
    _stateStream ??= _stateEvent.receiveBroadcastStream().map((event) {
      final index = event as int? ?? 0;
      return VpnState.values[index.clamp(0, VpnState.values.length - 1)];
    });
    return _stateStream!;
  }

  Stream<TrafficStats> get trafficStream {
    _trafficStream ??= _trafficEvent.receiveBroadcastStream().map((event) {
      return TrafficStats.fromMap(event as Map);
    });
    return _trafficStream!;
  }

  Future<void> connect() => _method.invokeMethod('connect');
  Future<void> disconnect() => _method.invokeMethod('disconnect');

  Future<VpnState> getState() async {
    final index = await _method.invokeMethod<int>('getState') ?? 0;
    return VpnState.values[index.clamp(0, VpnState.values.length - 1)];
  }

  Future<List<ClashProfile>> getProfiles() async {
    final list = await _method.invokeMethod<List>('getProfiles') ?? [];
    return list.map((e) => ClashProfile.fromMap(e as Map)).toList();
  }

  Future<ClashProfile?> getSelectedProfile() async {
    final map = await _method.invokeMethod<Map>('getSelectedProfile');
    return map != null ? ClashProfile.fromMap(map) : null;
  }

  Future<void> addSubscription(String name, String url) =>
      _method.invokeMethod('addSubscription', {'name': name, 'url': url});

  Future<void> updateSubscription(int id, String name, String url) =>
      _method.invokeMethod('updateSubscription', {'id': id, 'name': name, 'url': url});

  Future<void> deleteSubscription(int id) =>
      _method.invokeMethod('deleteSubscription', {'id': id});

  Future<void> selectProfile(int id) =>
      _method.invokeMethod('selectProfile', {'id': id});

  Future<void> refreshSubscription(int id) =>
      _method.invokeMethod('refreshSubscription', {'id': id});

  Future<void> refreshAll() => _method.invokeMethod('refreshAll');

  Future<void> updateProfileYaml(int id, String yamlContent) =>
      _method.invokeMethod('updateProfileYaml', {'id': id, 'yamlContent': yamlContent});

  /// Validate a clash config by handing it to meow-rs (never parsed in Dart).
  /// Returns `null` when the config is valid, otherwise the engine's error
  /// message.
  Future<String?> validateConfig(String yamlContent) =>
      _method.invokeMethod<String>('validateConfig', {'yamlContent': yamlContent});

  /// Open the system file picker and import the chosen YAML as a new local
  /// profile (validated by meow-rs on the native side). Returns the created
  /// profile, or `null` if the user cancelled. Throws [PlatformException] if
  /// the file can't be read or the config is invalid.
  Future<ClashProfile?> importConfig() async {
    final map = await _method.invokeMethod<Map>('importConfig');
    return map != null ? ClashProfile.fromMap(map) : null;
  }

  /// Save [yamlContent] to a user-chosen file via the system "create document"
  /// picker, suggesting `<name>.yaml`. Returns `true` once written, `false` if
  /// the user cancelled.
  Future<bool> exportConfig(String name, String yamlContent) async {
    final ok = await _method.invokeMethod<bool>(
      'exportConfig',
      {'name': name, 'yamlContent': yamlContent},
    );
    return ok ?? false;
  }

  Future<String> revertProfileYaml(int id) async {
    final result = await _method.invokeMethod<String>('revertProfileYaml', {'id': id});
    return result ?? '';
  }

  Future<List<Map<String, dynamic>>> getTrafficHistory() async {
    final list = await _method.invokeMethod<List>('getTrafficHistory') ?? [];
    return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  Future<List<Map<String, dynamic>>> getInstalledApps() async {
    final list = await _method.invokeMethod<List>('getInstalledApps') ?? [];
    return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  Future<Uint8List?> getAppIcon(String packageName) async {
    return await _method.invokeMethod<Uint8List>('getAppIcon', {'packageName': packageName});
  }

  Future<Map<String, dynamic>> getPerAppConfig() async {
    final map = await _method.invokeMethod<Map>('getPerAppConfig') ?? {};
    return Map<String, dynamic>.from(map);
  }

  Future<void> setPerAppConfig(String mode, List<String> packages) =>
      _method.invokeMethod('setPerAppConfig', {
        'mode': mode,
        'packages': json.encode(packages),
      });
}
