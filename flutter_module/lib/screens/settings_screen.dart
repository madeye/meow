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
          const _NetworkPrefsToggles(),
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

class _NetworkPrefsToggles extends StatefulWidget {
  const _NetworkPrefsToggles();

  @override
  State<_NetworkPrefsToggles> createState() => _NetworkPrefsTogglesState();
}

class _NetworkPrefsTogglesState extends State<_NetworkPrefsToggles> {
  static const _method = MethodChannel('io.github.madeye.meow/vpn');

  bool? _blockQuic;
  bool? _disableIpv6;

  @override
  void initState() {
    super.initState();
    _method.invokeMethod('getNetworkPrefs').then((map) {
      if (mounted) {
        setState(() {
          _blockQuic = map['blockQuic'] as bool? ?? false;
          _disableIpv6 = map['disableIpv6'] as bool? ?? false;
        });
      }
    });
  }

  Future<void> _update(bool blockQuic, bool disableIpv6) async {
    setState(() {
      _blockQuic = blockQuic;
      _disableIpv6 = disableIpv6;
    });
    await _method.invokeMethod('setNetworkPrefs', {
      'blockQuic': blockQuic,
      'disableIpv6': disableIpv6,
    });
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(S.of(context).reconnectToApply),
          behavior: SnackBarBehavior.floating,
          margin: const EdgeInsets.fromLTRB(48, 0, 48, 80),
          duration: const Duration(seconds: 2),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Column(
      children: [
        SwitchListTile(
          secondary: const Icon(Icons.block),
          title: Text(s.blockQuic),
          subtitle: Text(s.blockQuicDesc),
          value: _blockQuic ?? false,
          onChanged: _blockQuic == null
              ? null
              : (v) => _update(v, _disableIpv6!),
        ),
        SwitchListTile(
          secondary: const Icon(Icons.signal_cellular_off),
          title: Text(s.disableIpv6),
          subtitle: Text(s.disableIpv6Desc),
          value: _disableIpv6 ?? false,
          onChanged: _disableIpv6 == null
              ? null
              : (v) => _update(_blockQuic!, v),
        ),
      ],
    );
  }
}
