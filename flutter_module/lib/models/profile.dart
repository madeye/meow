import 'dart:convert';

class ClashProfile {
  final int id;
  String name;
  String url;
  String yamlContent;
  bool selected;
  int lastUpdated;
  int tx;
  int rx;
  String selectedProxy;
  String yamlBackup;
  Map<String, String> selectedProxies;

  ClashProfile({
    this.id = 0,
    this.name = '',
    this.url = '',
    this.yamlContent = '',
    this.selected = false,
    this.lastUpdated = 0,
    this.tx = 0,
    this.rx = 0,
    this.selectedProxy = '',
    this.yamlBackup = '',
    Map<String, String>? selectedProxies,
  }) : selectedProxies = selectedProxies ?? {};

  factory ClashProfile.fromMap(Map<dynamic, dynamic> map) {
    Map<String, String> parsedProxies = {};
    final raw = map['selectedProxies'];
    if (raw is String && raw.isNotEmpty) {
      try {
        final decoded = json.decode(raw);
        if (decoded is Map) {
          parsedProxies = decoded.map(
            (k, v) => MapEntry(k.toString(), v.toString()),
          );
        }
      } catch (_) {}
    }
    return ClashProfile(
      id: map['id'] as int? ?? 0,
      name: map['name'] as String? ?? '',
      url: map['url'] as String? ?? '',
      yamlContent: map['yamlContent'] as String? ?? '',
      selected: map['selected'] as bool? ?? false,
      lastUpdated: map['lastUpdated'] as int? ?? 0,
      tx: map['tx'] as int? ?? 0,
      rx: map['rx'] as int? ?? 0,
      selectedProxy: map['selectedProxy'] as String? ?? '',
      yamlBackup: map['yamlBackup'] as String? ?? '',
      selectedProxies: parsedProxies,
    );
  }

  Map<String, dynamic> toMap() => {
        'id': id,
        'name': name,
        'url': url,
        'yamlContent': yamlContent,
        'selected': selected,
        'lastUpdated': lastUpdated,
        'tx': tx,
        'rx': rx,
        'selectedProxy': selectedProxy,
        'yamlBackup': yamlBackup,
        'selectedProxies': json.encode(selectedProxies),
      };

  bool get hasBackup => yamlBackup.isNotEmpty && yamlBackup != yamlContent;
}
