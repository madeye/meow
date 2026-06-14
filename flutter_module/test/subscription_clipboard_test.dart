import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_module/screens/subscriptions_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const vpnChannel = MethodChannel('io.github.madeye.meow/vpn');

  // Clipboard / snackbar access goes through SystemChannels.platform.
  String? clipboardText;

  setUp(() {
    clipboardText = null;
    // No subscriptions, so the screen shows the empty state with an Add button.
    messenger.setMockMethodCallHandler(vpnChannel, (call) async {
      if (call.method == 'getProfiles') return <Object?>[];
      return null;
    });
    messenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.getData') {
        return clipboardText == null ? null : {'text': clipboardText};
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(vpnChannel, null);
    messenger.setMockMethodCallHandler(SystemChannels.platform, null);
  });

  Future<void> openAddDialog(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: SubscriptionsScreen()));
    await tester.pumpAndSettle();
    // Open the add/edit dialog via the empty-state button.
    await tester.tap(find.widgetWithText(FilledButton, 'Add Subscription'));
    await tester.pumpAndSettle();
  }

  Finder pasteButton() => find.byTooltip('Paste from clipboard');

  testWidgets('paste button fills the URL field from the clipboard',
      (tester) async {
    clipboardText = '  https://example.com/sub.yaml  ';
    await openAddDialog(tester);

    expect(pasteButton(), findsOneWidget);
    await tester.tap(pasteButton());
    await tester.pumpAndSettle();

    // Trimmed clipboard text is now shown in the URL field.
    expect(find.text('https://example.com/sub.yaml'), findsOneWidget);
  });

  testWidgets('paste button shows a snackbar when the clipboard is empty',
      (tester) async {
    clipboardText = null; // empty clipboard
    await openAddDialog(tester);

    await tester.tap(pasteButton());
    await tester.pumpAndSettle();

    expect(find.text('Clipboard is empty'), findsOneWidget);
    // The URL field stays empty (hint text remains).
    expect(find.text('https://...'), findsOneWidget);
  });
}
