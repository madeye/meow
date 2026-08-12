import 'package:flutter/material.dart';
import '../l10n/strings.dart';
import '../models/rule.dart';
import '../services/mihomo_api.dart';

class RulesScreen extends StatefulWidget {
  final Future<List<Rule>> Function()? getRulesOverride;

  const RulesScreen({super.key, this.getRulesOverride});

  @override
  State<RulesScreen> createState() => _RulesScreenState();
}

class _RulesScreenState extends State<RulesScreen> {
  List<Rule> _rules = [];
  String _filter = '';
  bool _loaded = false;
  bool _error = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final getRules = widget.getRulesOverride ?? MihomoApi.instance.getRules;
      final rules = await getRules();
      if (mounted) {
        setState(() {
          _rules = rules;
          _loaded = true;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loaded = true;
          _error = true;
        });
      }
    }
  }

  List<Rule> get _filtered {
    if (_filter.isEmpty) return _rules;
    final q = _filter.toLowerCase();
    return _rules
        .where(
          (r) =>
              r.type.toLowerCase().contains(q) ||
              r.payload.toLowerCase().contains(q) ||
              r.proxy.toLowerCase().contains(q),
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final items = _filtered;

    return Scaffold(
      appBar: AppBar(
        title: Text(_loaded ? s.rulesCount(_rules.length) : s.rules),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(48),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              decoration: InputDecoration(
                hintText: s.filterRules,
                prefixIcon: const Icon(Icons.search, size: 20),
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(vertical: 8),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              onChanged: (v) => setState(() => _filter = v),
            ),
          ),
        ),
      ),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : _error
          ? Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.error_outline,
                      size: 48,
                      color: Theme.of(context).colorScheme.error),
                  const SizedBox(height: 12),
                  Text(
                    s.rulesLoadError,
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: () {
                      setState(() {
                        _loaded = false;
                        _error = false;
                      });
                      _load();
                    },
                    icon: const Icon(Icons.refresh),
                    label: Text(s.retry),
                  ),
                ],
              ),
            )
          : items.isEmpty
          ? Center(
              child: Text(
                s.noRules,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            )
          : ListView.builder(
              itemCount: items.length,
              itemBuilder: (context, index) => _RuleTile(rule: items[index]),
            ),
    );
  }
}

class _RuleTile extends StatelessWidget {
  final Rule rule;

  const _RuleTile({required this.rule});

  static Color _proxyColor(String proxy) {
    switch (proxy.toUpperCase()) {
      case 'DIRECT':
        return Colors.greenAccent;
      case 'REJECT':
        return Colors.redAccent;
      default:
        return Colors.blueAccent;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = _proxyColor(rule.proxy);
    return ListTile(
      dense: true,
      leading: Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: color.withAlpha(30),
          border: Border.all(color: color.withAlpha(120)),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          rule.type,
          style: TextStyle(
            fontSize: 10,
            fontWeight: FontWeight.w600,
            color: color,
          ),
        ),
      ),
      title: Text(
        rule.payload.isNotEmpty ? rule.payload : '—',
        style: const TextStyle(fontSize: 13),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Text(rule.proxy, style: TextStyle(fontSize: 12, color: color)),
    );
  }
}
