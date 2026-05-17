import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'l10n/strings.dart';
import 'screens/home_screen.dart';
import 'screens/subscriptions_screen.dart';
import 'screens/traffic_screen.dart';
import 'screens/settings_screen.dart';

final profileChanged = ValueNotifier<int>(0);

void notifyProfileChanged() => profileChanged.value++;

class MihomoApp extends StatelessWidget {
  const MihomoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Meow',
      debugShowCheckedModeBanner: false,
      supportedLocales: const [Locale('en'), Locale('zh', 'CN')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: ThemeData(useMaterial3: true).copyWith(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFE8843A),
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: const Color(0xFFFFF1E0),
      ),
      darkTheme: ThemeData.dark(useMaterial3: true).copyWith(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFFFA458),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF1A140E),
      ),
      home: const MainScreen(),
    );
  }
}

class MainScreen extends StatefulWidget {
  const MainScreen({super.key});

  @override
  State<MainScreen> createState() => _MainScreenState();
}

class _MainScreenState extends State<MainScreen> {
  int _currentIndex = 0;

  final _screens = const [
    HomeScreen(),
    SubscriptionsScreen(),
    TrafficScreen(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Scaffold(
      body: IndexedStack(index: _currentIndex, children: _screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _currentIndex,
        onDestinationSelected: (i) => setState(() => _currentIndex = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.home), label: s.home),
          NavigationDestination(icon: const Icon(Icons.dns), label: s.subscribe),
          NavigationDestination(icon: const Icon(Icons.show_chart), label: s.traffic),
          NavigationDestination(icon: const Icon(Icons.settings), label: s.settings),
        ],
      ),
    );
  }
}
