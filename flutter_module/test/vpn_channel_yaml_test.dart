import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_module/services/vpn_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('io.github.madeye.meow/vpn');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  group('VpnChannel.updateProfileYaml', () {
    test('invokes updateProfileYaml with id and yamlContent', () async {
      MethodCall? captured;
      messenger.setMockMethodCallHandler(channel, (call) async {
        captured = call;
        return null;
      });

      await VpnChannel.instance.updateProfileYaml(42, 'a: 1\n');

      expect(captured, isNotNull);
      expect(captured!.method, 'updateProfileYaml');
      expect(captured!.arguments, {'id': 42, 'yamlContent': 'a: 1\n'});
    });
  });

  group('VpnChannel.validateConfig', () {
    test('passes yaml to native validateConfig and returns null when valid',
        () async {
      MethodCall? captured;
      messenger.setMockMethodCallHandler(channel, (call) async {
        captured = call;
        return null; // native returns null for a valid config
      });

      final error = await VpnChannel.instance.validateConfig('a: 1\n');

      expect(captured!.method, 'validateConfig');
      expect(captured!.arguments, {'yamlContent': 'a: 1\n'});
      expect(error, isNull);
    });

    test('returns the engine error message when invalid', () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => 'validate config: missing field `type`',
      );

      final error = await VpnChannel.instance.validateConfig('bad: yaml');
      expect(error, 'validate config: missing field `type`');
    });
  });

  group('VpnChannel.importConfig', () {
    test('returns the imported profile from the native map', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'importConfig');
        return {'id': 5, 'name': 'Imported', 'yamlContent': 'a: 1\n'};
      });

      final profile = await VpnChannel.instance.importConfig();
      expect(profile, isNotNull);
      expect(profile!.id, 5);
      expect(profile.name, 'Imported');
      expect(profile.yamlContent, 'a: 1\n');
    });

    test('returns null when the user cancels the picker', () async {
      messenger.setMockMethodCallHandler(channel, (call) async => null);
      expect(await VpnChannel.instance.importConfig(), isNull);
    });
  });

  group('VpnChannel.exportConfig', () {
    test('passes name and content, returns true when written', () async {
      MethodCall? captured;
      messenger.setMockMethodCallHandler(channel, (call) async {
        captured = call;
        return true;
      });

      final ok = await VpnChannel.instance.exportConfig('JP', 'proxies: []\n');
      expect(ok, isTrue);
      expect(captured!.method, 'exportConfig');
      expect(captured!.arguments, {'name': 'JP', 'yamlContent': 'proxies: []\n'});
    });

    test('returns false when the user cancels', () async {
      messenger.setMockMethodCallHandler(channel, (call) async => false);
      expect(await VpnChannel.instance.exportConfig('x', 'y'), isFalse);
    });
  });

  group('VpnChannel.revertProfileYaml', () {
    test('returns reverted yaml from native side', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'revertProfileYaml');
        expect(call.arguments, {'id': 9});
        return 'pristine: true\n';
      });

      final result = await VpnChannel.instance.revertProfileYaml(9);
      expect(result, 'pristine: true\n');
    });

    test('returns empty string when native returns null', () async {
      messenger.setMockMethodCallHandler(channel, (call) async => null);
      final result = await VpnChannel.instance.revertProfileYaml(1);
      expect(result, '');
    });
  });
}
