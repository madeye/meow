import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../l10n/strings.dart';
import 'per_app_proxy_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  static const _method = MethodChannel('io.github.madeye.meow/vpn');

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(s.settings)),
      body: ListView(
        children: [
          _SectionHeader(s.general),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: Text(s.version),
            subtitle: FutureBuilder<String?>(
              future: _method.invokeMethod<String>('getAppVersion'),
              builder: (_, snap) => Text(snap.data ?? '...'),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.memory),
            title: Text(s.engine),
            subtitle: FutureBuilder<String?>(
              future: _method.invokeMethod<String>('getVersion'),
              builder: (_, snap) => Text(snap.data ?? '...'),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.apps),
            title: Text(s.perAppProxy),
            subtitle: Text(s.perAppProxyDesc),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const PerAppProxyScreen()),
            ),
          ),
          _SectionHeader(s.network),
          ListTile(
            leading: const Icon(Icons.dns),
            title: Text(s.dnsServer),
            subtitle: Text(s.dnsBuiltIn),
          ),
          _SectionHeader(s.about),
          ListTile(
            leading: const Icon(Icons.code),
            title: Text(s.sourceCode),
            subtitle: Text(s.sourceCodeUrl),
            onTap: () {},
          ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  const _SectionHeader(this.title);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: TextStyle(
          color: Theme.of(context).colorScheme.primary,
          fontWeight: FontWeight.w600,
          fontSize: 13,
        ),
      ),
    );
  }
}
