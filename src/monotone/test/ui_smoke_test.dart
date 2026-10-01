import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:monotone/app_state.dart';
import 'package:monotone/library.dart';
import 'package:monotone/playback.dart';
import 'package:monotone/ui/home.dart';
import 'package:monotone/ui/player_screen.dart';
import 'package:monotone/ui/widgets.dart';

EButton btn(WidgetTester t, String label) =>
    t.widget<EButton>(find.byWidgetPredicate((w) => w is EButton && w.label == label).first);

Future<void> loadFont(String family, List<String> files) async {
  final l = FontLoader(family);
  for (final f in files) {
    l.addFont(File(f).readAsBytes().then((b) => ByteData.sublistView(b)));
  }
  await l.load();
}

void main() {
  setUp(() {
    // No native audio in unit tests: stub the just_audio channel.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('com.ryanheise.just_audio.methods'),
      (c) async => c.method == 'disposeAllPlayers' ? <String, dynamic>{} : null,
    );
  });

  setUpAll(() async {
    // Real glyph widths (Roboto, same as Android).
    await loadFont('Roboto', [
      '/opt/flutter/bin/cache/artifacts/material_fonts/Roboto-Regular.ttf',
      '/opt/flutter/bin/cache/artifacts/material_fonts/Roboto-Bold.ttf',
    ]);
    await loadFont('MaterialIcons', ['/opt/flutter/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf']);
  });

  for (final scale in [1.0, 1.3]) {
    testWidgets('every screen renders at text scale $scale; settings highlight updates', (t) async {
      if (!Directory('/tmp/lib').existsSync()) return;
      t.view.physicalSize = const Size(824, 1648); // Palma-ish portrait
      t.view.devicePixelRatio = 2;

      final tmp = Directory.systemTemp.createTempSync('mono');
      store = Store()
        ..dataDir = tmp
        ..coverDir = (Directory('${tmp.path}/covers')..createSync());
      store.settings.root = '/tmp/lib';
      store.books = [for (final j in scanLibrary('/tmp/lib', store.coverDir.path, {})) Book.fromJson(j)];
      playback = Playback(store);

      await t.pumpWidget(
        MaterialApp(
          theme: buildTheme(false),
          builder: (c, child) => MediaQuery(
            data: MediaQuery.of(c).copyWith(textScaler: TextScaler.linear(scale)),
            child: child!,
          ),
          home: const Home(),
        ),
      );
      await t.pump();

      // Shelves tab (default when... library non-empty -> Continue)
      await t.tap(find.text('Shelves'));
      await t.pump();
      expect(find.text('Fantasy'), findsOneWidget);
      await t.tap(find.text('Fantasy'));
      await t.pump();
      expect(find.text('The Way of Kings'), findsOneWidget);
      await t.tap(find.text('← Shelves'));
      await t.pump();

      // All tab + search
      await t.tap(find.text('All'));
      await t.pump();
      expect(find.text('3 books'), findsOneWidget);
      await t.enterText(find.byType(TextField), 'kings');
      await t.pump();
      expect(find.text('1 book'), findsOneWidget);

      // Files tab
      await t.tap(find.text('Files'));
      await t.pump();
      expect(find.text('▸  Fantasy'), findsOneWidget);

      // Settings: highlight must follow the tap (the reported bug)
      await t.tap(find.text('Settings'));
      await t.pump();
      expect(btn(t, '60s').selected, isFalse);
      await t.tap(find.text('60s').first);
      await t.pump();
      expect(btn(t, '60s').selected, isTrue);
      expect(store.settings.skipBack, 60);
      await t.scrollUntilVisible(find.text('White on black'), 200);
      await t.ensureVisible(find.text('White on black'));
      await t.pump();
      await t.tap(find.text('White on black'));
      await t.pump();
      expect(btn(t, 'White on black').selected, isTrue);

      // Player screen with a loaded book (audio backend absent in tests -> error text, UI still renders)
      await t.runAsync(
        () => playback
            .open(store.books.firstWhere((b) => b.title == 'The Way of Kings'), play: false)
            .timeout(const Duration(seconds: 5), onTimeout: () {}),
      );
      pushPage(t.element(find.byType(Home)), const PlayerScreen());
      await t.pump();
      await t.pump();
      expect(find.byIcon(Icons.play_arrow_rounded), findsWidgets);
      expect(find.text('Ch 1/3 · Prelude to the Stormlight Archive'), findsOneWidget);

      await t.tap(find.text('Chapters'));
      await t.pump();
      expect(find.text('Épilogue — Ünïcode'), findsOneWidget);
      await t.tap(find.text('← Back'));
      await t.pump();

      await t.tap(find.textContaining('Speed'));
      await t.pump();
      expect(find.text('Playback speed'), findsOneWidget);
      await t.tap(find.text('Done'));
      await t.pump();

      await t.tap(find.text('Sleep'));
      await t.pump();
      expect(find.text('End of this chapter'), findsOneWidget);
      await t.tap(find.text('Turn off'));
      await t.pump();

      // Landscape laptop layout
      t.view.physicalSize = const Size(2560, 1600);
      await t.pump();
      expect(find.byIcon(Icons.skip_next_rounded), findsOneWidget);

      playback.dispose();
      t.view.reset();
    });
  }
}
