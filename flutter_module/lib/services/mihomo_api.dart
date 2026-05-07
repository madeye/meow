import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:web_socket_channel/web_socket_channel.dart';
import '../models/proxy_group.dart';
import '../models/rule.dart';
import '../models/connection.dart';
import '../models/log_entry.dart';
import '../models/runtime_config.dart';
import '../models/proxy_provider.dart';
import '../models/traffic.dart';

/// Typed client for the mihomo external-controller REST API.
///
/// Base URL is always [_kBaseUrl] — the loopback listener of the embedded Rust
/// mihomo engine running inside the app process on-device.  No override is
/// provided or permitted; see team-lead policy 2026-04-11.
///
/// Production singleton: [MihomoApi.instance].
/// Test-only constructor: [MihomoApi.withClient].
class MihomoApi {
  // Embedded mihomo engine external-controller. Matches MihomoInstance.kt:41.
  static const String _kBaseUrl = 'http://127.0.0.1:9090';

  static MihomoApi? _instance;
  static MihomoApi get instance => _instance ??= MihomoApi._();

  final http.Client _client;

  MihomoApi._() : _client = http.Client();

  /// Test-only: injects a fake [http.Client] (mocks transport, not the engine).
  MihomoApi.withClient(this._client);

  Uri _uri(String path, [Map<String, String>? query]) {
    final uri = Uri.parse('$_kBaseUrl$path');
    return query != null ? uri.replace(queryParameters: query) : uri;
  }

  static const Map<String, String> _jsonHeaders = {
    'Content-Type': 'application/json',
  };

  // -------------------------------------------------------------------------
  // Proxies
  // -------------------------------------------------------------------------

  Future<ProxiesResult> getProxies() async {
    final res = await _client.get(_uri('/proxies'));
    _assertOk(res, 'getProxies');
    return ProxiesResult.parse(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<ProxiesResult> getProxy(String name) async {
    final res = await _client.get(_uri('/proxies/${Uri.encodeComponent(name)}'));
    _assertOk(res, 'getProxy');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return ProxiesResult.parse({'proxies': {name: body}});
  }

  Future<void> selectProxy(String group, String name) async {
    final res = await _client.put(
      _uri('/proxies/${Uri.encodeComponent(group)}'),
      headers: _jsonHeaders,
      body: jsonEncode({'name': name}),
    );
    _assertOk(res, 'selectProxy', okCodes: {200, 204});
  }

  Future<int> testProxyDelay(
    String name, {
    String url = 'http://www.gstatic.com/generate_204',
    int timeoutMs = 5000,
  }) async {
    final res = await _client.get(_uri(
      '/proxies/${Uri.encodeComponent(name)}/delay',
      {'url': url, 'timeout': '$timeoutMs'},
    ));
    _assertOk(res, 'testProxyDelay');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body['delay'] as int? ?? 0;
  }

  Future<Map<String, int>> testGroupDelay(
    String group, {
    String url = 'http://www.gstatic.com/generate_204',
    int timeoutMs = 60000, // 60s for large groups with many proxies
  }) async {
    final res = await _client.get(_uri(
      '/group/${Uri.encodeComponent(group)}/delay',
      {'url': url, 'timeout': '$timeoutMs'},
    ));
    _assertOk(res, 'testGroupDelay');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body.map((k, v) => MapEntry(k, (v as num?)?.toInt() ?? 0));
  }

  // -------------------------------------------------------------------------
  // Rules
  // -------------------------------------------------------------------------

  Future<List<Rule>> getRules() async {
    final res = await _client.get(_uri('/rules'));
    _assertOk(res, 'getRules');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return (body['rules'] as List<dynamic>? ?? [])
        .whereType<Map<String, dynamic>>()
        .map(Rule.fromJson)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Connections
  // -------------------------------------------------------------------------

  Future<ConnectionsSnapshot> getConnections() async {
    final res = await _client.get(_uri('/connections'));
    _assertOk(res, 'getConnections');
    return ConnectionsSnapshot.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<void> closeAllConnections() async {
    final req = http.Request('DELETE', _uri('/connections'));
    final streamed = await _client.send(req);
    await streamed.stream.drain<void>();
    _assertOkCode(streamed.statusCode, 'closeAllConnections',
        okCodes: {200, 204});
  }

  Future<void> closeConnection(String id) async {
    final req =
        http.Request('DELETE', _uri('/connections/${Uri.encodeComponent(id)}'));
    final streamed = await _client.send(req);
    await streamed.stream.drain<void>();
    _assertOkCode(streamed.statusCode, 'closeConnection', okCodes: {200, 204});
  }

  // -------------------------------------------------------------------------
  // Configs
  // -------------------------------------------------------------------------

  Future<RuntimeConfig> getConfigs() async {
    final res = await _client.get(_uri('/configs'));
    _assertOk(res, 'getConfigs');
    return RuntimeConfig.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<void> patchConfigs(Map<String, dynamic> patch) async {
    final res = await _client.patch(
      _uri('/configs'),
      headers: _jsonHeaders,
      body: jsonEncode(patch),
    );
    _assertOk(res, 'patchConfigs', okCodes: {200, 204});
  }

  // -------------------------------------------------------------------------
  // Providers
  // -------------------------------------------------------------------------

  Future<Map<String, ProxyProvider>> getProxyProviders() async {
    final res = await _client.get(_uri('/providers/proxies'));
    _assertOk(res, 'getProxyProviders');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    final raw = body['providers'] as Map<String, dynamic>? ?? {};
    return {
      for (final e in raw.entries)
        if (e.value is Map<String, dynamic>)
          e.key: ProxyProvider.fromJson(e.key, e.value as Map<String, dynamic>),
    };
  }

  Future<void> updateProxyProvider(String name) async {
    final res = await _client.put(
      _uri('/providers/proxies/${Uri.encodeComponent(name)}'),
      headers: _jsonHeaders,
      body: '{}',
    );
    _assertOk(res, 'updateProxyProvider', okCodes: {200, 204});
  }

  Future<Map<String, RuleProvider>> getRuleProviders() async {
    final res = await _client.get(_uri('/providers/rules'));
    _assertOk(res, 'getRuleProviders');
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    final raw = body['providers'] as Map<String, dynamic>? ?? {};
    return {
      for (final e in raw.entries)
        if (e.value is Map<String, dynamic>)
          e.key: RuleProvider.fromJson(e.key, e.value as Map<String, dynamic>),
    };
  }

  Future<void> updateRuleProvider(String name) async {
    final res = await _client.put(
      _uri('/providers/rules/${Uri.encodeComponent(name)}'),
      headers: _jsonHeaders,
      body: '{}',
    );
    _assertOk(res, 'updateRuleProvider', okCodes: {200, 204});
  }

  // -------------------------------------------------------------------------
  // DNS + Memory
  // -------------------------------------------------------------------------

  Future<DnsQueryResult> dnsQuery(String name, {String type = 'A'}) async {
    final res =
        await _client.get(_uri('/dns/query', {'name': name, 'type': type}));
    _assertOk(res, 'dnsQuery');
    return DnsQueryResult.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  /// NOTE: The Rust-based mihomo engine used here does NOT expose the
  /// /memory endpoint (that endpoint is Go-specific in meow-go). Callers
  /// already catch MihomoApiException silently, so no code change is needed —
  /// this will simply throw on every call with an HTTP error.
  Future<MemoryInfo> getMemory() async {
    final res = await _client.get(_uri('/memory'));
    _assertOk(res, 'getMemory');
    return MemoryInfo.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  // -------------------------------------------------------------------------
  // Streams — implemented in Task 6
  // -------------------------------------------------------------------------

  Stream<LogEntry> streamLogs({String level = 'info'}) => _streamJsonLines(
        Uri.parse(_kBaseUrl.replaceFirst('http', 'ws'))
            .replace(path: '/logs', queryParameters: {'level': level}),
        LogEntry.fromJson,
      );

  Stream<MihomoTraffic> streamTraffic() => _streamJsonLines(
        Uri.parse(_kBaseUrl.replaceFirst('http', 'ws'))
            .replace(path: '/traffic'),
        MihomoTraffic.fromJson,
      );

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  void _assertOk(http.Response res, String label,
      {Set<int> okCodes = const {200}}) =>
      _assertOkCode(res.statusCode, label, okCodes: okCodes);

  void _assertOkCode(int code, String label,
      {Set<int> okCodes = const {200}}) {
    if (!okCodes.contains(code)) throw MihomoApiException(label, code);
  }

  /// WebSocket -> `Stream<T>` with reconnect. Implemented in Task 6.
  Stream<T> _streamJsonLines<T>(
    Uri uri,
    T Function(Map<String, dynamic>) fromJson,
  ) {
    late StreamController<T> controller;
    WebSocketChannel? channel;
    bool cancelled = false;
    int backoffMs = 500;

    Future<void> connect() async {
      while (!cancelled) {
        try {
          channel = WebSocketChannel.connect(uri);
          await for (final raw in channel!.stream) {
            if (cancelled) return;
            if (raw is String) {
              final decoded = jsonDecode(raw);
              if (decoded is Map<String, dynamic>) {
                controller.add(fromJson(decoded));
              }
            }
          }
          if (!cancelled) backoffMs = 500;
        } catch (_) {
          if (cancelled) return;
        }
        if (!cancelled) {
          await Future.delayed(Duration(milliseconds: backoffMs));
          backoffMs = (backoffMs * 2).clamp(0, 30000);
        }
      }
    }

    controller = StreamController<T>(
      onListen: () { connect().catchError(controller.addError); },
      onCancel: () {
        cancelled = true;
        channel?.sink.close();
      },
    );
    return controller.stream;
  }
}

class MihomoApiException implements Exception {
  final String operation;
  final int statusCode;
  const MihomoApiException(this.operation, this.statusCode);

  @override
  String toString() =>
      'MihomoApiException: $operation returned HTTP $statusCode';
}
