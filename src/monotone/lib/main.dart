import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:audio_service/audio_service.dart';
import 'package:just_audio_media_kit/just_audio_media_kit.dart';

import 'app_state.dart';
import 'library.dart';
import 'media_handler.dart';
import 'playback.dart';
import 'ui/home.dart';
import 'ui/player_screen.dart';
import 'ui/widgets.dart';

final navKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!Platform.isAndroid) {
    JustAudioMediaKit.title = 'Monotone';
    JustAudioMediaKit.ensureInitialized(windows: true, linux: false, macOS: false);
  }
  store = await Store.load();
  playback = Playback(store);
  if (Platform.isAndroid) {
    await AudioService.init(
      builder: () => MonotoneHandler(playback),
      config: const AudioServiceConfig(
        androidNotificationChannelId: 'com.jacob.monotone.playback',
        androidNotificationChannelName: 'Playback',
        androidNotificationIcon: 'mipmap/ic_launcher',
        androidNotificationOngoing: true,
        androidStopForegroundOnPause: true,
        fastForwardInterval: Duration(seconds: 30),
        rewindInterval: Duration(seconds: 30),
      ),
    );
  }
  await playback.restoreLast();
  runApp(const MonotoneApp());
  if (store.settings.root != null && !Platform.isAndroid) store.rescan();
}

class MonotoneApp extends StatelessWidget {
  const MonotoneApp({super.key});

  void _withNav(void Function(BuildContext) f) {
    final c = navKey.currentContext;
    if (c != null) f(c);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final s = store.settings;
        return MaterialApp(
          title: 'Monotone',
          navigatorKey: navKey,
          debugShowCheckedModeBanner: false,
          theme: buildTheme(s.invert),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(s.textScale)),
            child: CallbackShortcuts(
              bindings: {
                const SingleActivator(LogicalKeyboardKey.space): playback.togglePlay,
                const SingleActivator(LogicalKeyboardKey.mediaPlayPause): playback.togglePlay,
                const SingleActivator(LogicalKeyboardKey.arrowLeft): playback.skipBack,
                const SingleActivator(LogicalKeyboardKey.arrowRight): playback.skipForward,
                const SingleActivator(LogicalKeyboardKey.bracketLeft): playback.prevChapter,
                const SingleActivator(LogicalKeyboardKey.bracketRight): playback.nextChapter,
                const SingleActivator(LogicalKeyboardKey.mediaTrackPrevious): playback.prevChapter,
                const SingleActivator(LogicalKeyboardKey.mediaTrackNext): playback.nextChapter,
                const SingleActivator(LogicalKeyboardKey.minus): () => playback.setSpeed(playback.speed - 0.05),
                const SingleActivator(LogicalKeyboardKey.equal): () => playback.setSpeed(playback.speed + 0.05),
                const SingleActivator(LogicalKeyboardKey.keyB): () => _withNav((c) {
                  if (playback.book != null) showAddBookmark(c);
                }),
                const SingleActivator(LogicalKeyboardKey.keyP): () =>
                    _withNav((c) => pushPage(c, const PlayerScreen())),
              },
              child: Focus(autofocus: true, child: child ?? const SizedBox()),
            ),
          ),
          home: const Home(),
        );
      },
    );
  }
}
