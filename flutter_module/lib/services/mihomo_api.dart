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
    String url = 'https://cp.cloudflare.com/generate_204',
    int timeoutMs = 5000,
  }) async {
    for (var attempt = 0;; attempt++) {
      final res = await _client.get(_uri(
        '/proxies/${Uri.encodeComponent(name)}/delay',
        {'url': url, 'timeout': '$timeoutMs'},
      ))
          .timeout(Duration(milliseconds: timeoutMs + 2000));
      if (res.statusCode == 200) {
        final body = jsonDecode(res.body) as Map<String, dynamic>;
        return body['delay'] as int? ?? 0;
      }
      // 503 = the engine reports a transient transport error (e.g. the
      // VPN bypass path is still settling right after cold-start connect,
      // or a momentary DNS upstream blip). Retry with exponential backoff
      // (500 ms / 1 s / 2 s) so the user does not see a spurious failure
      // during the settling window. 504 (timeout) and 404 (not found) are
      // not transient — fail fast via _assertOk.
      if (res.statusCode == 503 && attempt < 3) {
        await Future.delayed(Duration(milliseconds: 500 << attempt));
        continue;
      }
      _assertOk(res, 'testProxyDelay');
    }
  }

  /// Probe every member of [members] for latency concurrently.
  ///
  /// Probes each member individually via /proxies/{name}/delay instead of the
  /// /group/{name}/delay batch endpoint. The batch endpoint returns HTTP 504
  /// and discards *every* member's result when a single one times out
  /// (matching upstream mihomo's getGroupDelay), so one dead proxy would blank
  /// out the whole group in the speed-test panel. Per-member probing keeps
  /// each result independent. The per-member timeout defaults to 15 s,
  /// which leaves enough time for slow protocol handshakes without making a
  /// dead member hold the group busy for a full minute. When [onMemberDone]
  /// is supplied it is awaited after each member's probe (success or failure),
  /// so the caller can refresh the UI incrementally as delays land rather than
  /// waiting for the whole group.
  Future<void> testGroupDelay(
    List<String> members, {
    String url = 'https://cp.cloudflare.com/generate_204',
    int timeoutMs = 15000,
    Future<void> Function(String name, int delay)? onMemberDone,
  }) async {
    // Return each member's measured delay to the caller rather than relying
    // on a /proxies history re-fetch: probing /proxies/{group}/delay for a
    // member that is itself a sub-group returns the delay but (in this
    // engine) does NOT persist it into the group's history field, so a
    // history-based display would stay blank for sub-group members.  The
    // returned value is authoritative for the just-run probe.
    await Future.wait(
      members.map((name) async {
        var delay = 0;
        try {
          delay = await testProxyDelay(name, url: url, timeoutMs: timeoutMs);
        } catch (_) {
          // Per-member failure (timeout/transport): leave delay 0
          // (untested) and keep probing the rest.
        }
        await onMemberDone?.call(name, delay);
      }),
    );
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
