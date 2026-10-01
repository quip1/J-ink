import 'dart:io';
import 'dart:ui' show AppExitResponse;

import 'package:flutter/material.dart';
import 'package:path/path.dart' as p;
import 'package:flutter/services.dart';

import '../app_state.dart';
import '../library.dart';
import 'player_screen.dart';
import 'widgets.dart';

const storageChannel = MethodChannel('monotone/storage');

void openBook(BuildContext context, Book b) {
  playback.open(b);
  pushPage(context, const PlayerScreen());
}

class Home extends StatefulWidget {
  const Home({super.key});
  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> {
  int tab = 0;
  bool? _hasAccess;
  late final AppLifecycleListener _life;

  @override
  void initState() {
    super.initState();
    _checkAccess();
    _life = AppLifecycleListener(
      onResume: _checkAccess,
      onPause: playback.saveNow,
      onDetach: playback.saveNow,
      onExitRequested: () async {
        await playback.saveNow();
        return AppExitResponse.exit;
      },
    );
    if (store.books.isEmpty) tab = 1;
  }

  @override
  void dispose() {
    _life.dispose();
    super.dispose();
  }

  Future<void> _checkAccess() async {
    if (!Platform.isAndroid) {
      setState(() => _hasAccess = true);
      return;
    }
    final ok = await storageChannel.invokeMethod<bool>('hasAccess') ?? false;
    final first = _hasAccess != true && ok;
    if (!mounted) return;
    setState(() => _hasAccess = ok);
    if (first) {
      storageChannel.invokeMethod('requestNotifications');
      final exempt = await storageChannel.invokeMethod<bool>('isBatteryExempt') ?? true;
      if (!exempt && !store.settings.askedBattery) {
        store.settings.askedBattery = true;
        store.saveSettings();
        storageChannel.invokeMethod('requestBatteryExempt');
      }
      if (store.settings.root != null) store.rescan();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_hasAccess == null) return const Scaffold();
    if (_hasAccess == false) return const _AccessGate();
    return Scaffold(
      body: SafeArea(
        child: ListenableBuilder(
          listenable: Listenable.merge([store, playback]),
          builder: (context, _) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(10, 10, 10, 0),
                child: ETabBar(
                  labels: const ['Continue', 'Shelves', 'All', 'Files', 'Settings'],
                  index: tab,
                  onTap: (i) => setState(() => tab = i),
                ),
              ),
              if (store.scanning)
                const Padding(
                  padding: EdgeInsets.only(top: 6),
                  child: Text('Scanning library…', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                ),
              if (store.scanError != null)
                Padding(
                  padding: const EdgeInsets.all(6),
                  child: Text('Scan failed: ${store.scanError}', style: const TextStyle(fontSize: 14)),
                ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(10, 4, 10, 6),
                  // Not const on purpose: these must rebuild whenever the store changes.
                  child: switch (tab) {
                    0 => _ContinueTab(),
                    1 => _ShelvesTab(),
                    2 => _AllTab(),
                    3 => const FileBrowser(),
                    _ => SettingsTab(),
                  },
                ),
              ),
              const MiniPlayer(),
            ],
          ),
        ),
      ),
    );
  }
}

class _AccessGate extends StatelessWidget {
  const _AccessGate();
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Monotone', style: TextStyle(fontSize: 34, fontWeight: FontWeight.w900)),
              const SizedBox(height: 16),
              const Text(
                'To read your audiobook folders, Monotone needs "All files access".\n\n'
                'Tap below, then switch it on for Monotone and come back.',
                style: TextStyle(fontSize: 18),
              ),
              const SizedBox(height: 24),
              EButton(
                'Grant file access',
                big: true,
                selected: true,
                fontSize: 20,
                onTap: () async {
                  await storageChannel.invokeMethod('requestAccess');
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _NoLibrary extends StatelessWidget {
  const _NoLibrary();
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('No library folder yet.', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          const Text(
            'Pick the folder that holds your audiobooks.\nEach subfolder becomes a shelf.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 16),
          ),
          const SizedBox(height: 16),
          EButton('Choose library folder', selected: true, onTap: () => pickLibraryRoot(context)),
        ],
      ),
    );
  }
}

Future<void> pickLibraryRoot(BuildContext context) async {
  final path = await pushPage<String>(context, const FolderPickerPage());
  if (path == null) return;
  store.settings.root = path;
  store.saveSettings();
  await store.rescan();
}

// ---------- tabs ----------
class _ContinueTab extends StatelessWidget {
  const _ContinueTab();
  @override
  Widget build(BuildContext context) {
    if (store.settings.root == null) return const _NoLibrary();
    final list = store.inProgress;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SectionTitle('Continue listening'),
        Expanded(
          child: PagedList(
            itemCount: list.length,
            itemHeight: BookRow.height,
            emptyText: 'Nothing in progress.\nPick something from Shelves or All.',
            itemBuilder: (context, i) => BookRow(
              book: list[i],
              onTap: () => openBook(context, list[i]),
              onLongPress: () => showBookMenu(context, list[i]),
            ),
          ),
        ),
      ],
    );
  }
}

class _ShelvesTab extends StatelessWidget {
  const _ShelvesTab();
  @override
  Widget build(BuildContext context) {
    if (store.settings.root == null) return const _NoLibrary();
    final shelves = store.shelves;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionTitle('Shelves', trailing: EButton('Rescan', onTap: store.scanning ? null : store.rescan)),
        Expanded(
          child: PagedList(
            itemCount: shelves.length,
            itemHeight: 64,
            emptyText: store.scanning ? 'Scanning…' : 'No audiobooks found in\n${store.settings.root}',
            itemBuilder: (context, i) {
              final name = shelves[i];
              final books = store.books.where((b) => b.shelf == name).toList();
              final going = books.where((b) {
                final st = store.states[b.path];
                return st != null && st.started && !st.finished;
              }).length;
              final done = books.where((b) => store.states[b.path]?.finished == true).length;
              return GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => pushPage(context, ShelfPage(shelf: name)),
                child: Container(
                  decoration: BoxDecoration(
                    border: Border(bottom: BorderSide(color: fgOf(context), width: 1)),
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800),
                            ),
                            Text(
                              [
                                '${books.length} book${books.length == 1 ? '' : 's'}',
                                if (going > 0) '$going in progress',
                                if (done > 0) '$done done',
                              ].join(' · '),
                              style: const TextStyle(fontSize: 15),
                            ),
                          ],
                        ),
                      ),
                      const Text('›', style: TextStyle(fontSize: 30, fontWeight: FontWeight.w800)),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SortButton extends StatelessWidget {
  const _SortButton();
  static const _order = ['title', 'author', 'recent', 'progress'];
  static const _labels = {'title': 'Title', 'author': 'Author', 'recent': 'Recent', 'progress': 'Progress'};
  @override
  Widget build(BuildContext context) {
    final cur = store.settings.sort;
    return EButton(
      'Sort: ${_labels[cur]}',
      onTap: () {
        store.settings.sort = _order[(_order.indexOf(cur) + 1) % _order.length];
        store.saveSettings();
      },
    );
  }
}

class ShelfPage extends StatelessWidget {
  final String shelf;
  const ShelfPage({super.key, required this.shelf});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: ListenableBuilder(
          listenable: Listenable.merge([store, playback]),
          builder: (context, _) {
            final list = store.sorted(store.books.where((b) => b.shelf == shelf));
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(10, 10, 10, 0),
                  child: Row(
                    children: [
                      EButton('← Shelves', onTap: () => Navigator.pop(context)),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          shelf,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
                        ),
                      ),
                      const _SortButton(),
                    ],
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(10, 8, 10, 6),
                    child: PagedList(
                      itemCount: list.length,
                      itemHeight: BookRow.height,
                      itemBuilder: (context, i) => BookRow(
                        book: list[i],
                        onTap: () => openBook(context, list[i]),
                        onLongPress: () => showBookMenu(context, list[i]),
                      ),
                    ),
                  ),
                ),
                const MiniPlayer(),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _AllTab extends StatefulWidget {
  const _AllTab();
  @override
  State<_AllTab> createState() => _AllTabState();
}

class _AllTabState extends State<_AllTab> {
  String q = '';
  @override
  Widget build(BuildContext context) {
    if (store.settings.root == null) return const _NoLibrary();
    final ql = q.toLowerCase();
    final list = store.sorted(
      store.books.where(
        (b) =>
            ql.isEmpty ||
            b.title.toLowerCase().contains(ql) ||
            b.author.toLowerCase().contains(ql) ||
            b.narrator.toLowerCase().contains(ql) ||
            b.shelf.toLowerCase().contains(ql),
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 6),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  decoration: const InputDecoration(hintText: 'Search title, author, narrator'),
                  style: const TextStyle(fontSize: 17),
                  onChanged: (v) => setState(() => q = v),
                ),
              ),
              const SizedBox(width: 6),
              const _SortButton(),
            ],
          ),
        ),
        Text(
          '${list.length} book${list.length == 1 ? '' : 's'}',
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
        ),
        Expanded(
          child: PagedList(
            key: ValueKey(q),
            itemCount: list.length,
            itemHeight: BookRow.height,
            emptyText: q.isEmpty ? 'No books yet.' : 'No matches.',
            itemBuilder: (context, i) => BookRow(
              book: list[i],
              onTap: () => openBook(context, list[i]),
              onLongPress: () => showBookMenu(context, list[i]),
            ),
          ),
        ),
      ],
    );
  }
}

// ---------- file browser ----------
String defaultStartDir() {
  final root = store.settings.root;
  if (root != null && Directory(root).existsSync()) return root;
  if (Platform.isAndroid) return '/storage/emulated/0';
  return Platform.environment['USERPROFILE'] ?? 'C:\\';
}

List<String> windowsDrives() => [
  for (var c = 65; c <= 90; c++)
    if (Directory('${String.fromCharCode(c)}:\\').existsSync()) '${String.fromCharCode(c)}:\\',
];

class FileBrowser extends StatefulWidget {
  final bool pickFolder;
  const FileBrowser({super.key, this.pickFolder = false});
  @override
  State<FileBrowser> createState() => _FileBrowserState();
}

class _FileBrowserState extends State<FileBrowser> {
  String? dir; // null on Windows = drive list
  List<FileSystemEntity> entries = [];
  String? error;

  @override
  void initState() {
    super.initState();
    _go(defaultStartDir());
  }

  void _go(String? d) {
    error = null;
    entries = [];
    if (d != null) {
      try {
        final list =
            Directory(d).listSync(followLinks: false).where((e) {
              final n = p.basename(e.path);
              if (n.startsWith('.') || n.startsWith(r'$')) return false;
              if (e is Directory) return true;
              return !widget.pickFolder && isAudio(e.path);
            }).toList()..sort((a, b) {
              final ad = a is Directory, bd = b is Directory;
              if (ad != bd) return ad ? -1 : 1;
              return p.basename(a.path).toLowerCase().compareTo(p.basename(b.path).toLowerCase());
            });
        entries = list;
      } catch (e) {
        error = "Can't open this folder.";
      }
    }
    setState(() => dir = d);
  }

  void _up() {
    final d = dir;
    if (d == null) return;
    final parent = p.dirname(d);
    if (parent == d) {
      if (Platform.isWindows) _go(null);
      return;
    }
    if (Platform.isAndroid && d == '/storage/emulated/0') {
      _go('/storage');
      return;
    }
    _go(parent);
  }

  Future<void> _openFile(String path) async {
    var b = store.bookAt(path);
    if (b == null) {
      try {
        b = await loadLooseBook(path, store.coverDir.path);
      } catch (_) {
        b = Book(path: path, title: p.basenameWithoutExtension(path));
      }
    }
    if (mounted) openBook(context, b);
  }

  @override
  Widget build(BuildContext context) {
    final d = dir;
    final isDrives = d == null;
    final drives = isDrives ? windowsDrives() : const <String>[];
    final count = isDrives ? drives.length : entries.length;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 8),
        Text(
          isDrives ? 'This PC' : d,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 6),
        Row(
          children: [
            EButton('↑ Up', onTap: isDrives ? null : _up),
            const SizedBox(width: 6),
            EButton('Home', onTap: () => _go(defaultStartDir())),
            const SizedBox(width: 6),
            if (!isDrives)
              Flexible(
                child: Align(
                  alignment: Alignment.centerRight,
                  child: EButton(
                    widget.pickFolder ? 'Use this folder' : 'Set as library',
                    selected: widget.pickFolder,
                    onTap: () async {
                      if (widget.pickFolder) {
                        Navigator.pop(context, d);
                        return;
                      }
                      store.settings.root = d;
                      store.saveSettings();
                      await store.rescan();
                    },
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 6),
        if (error != null)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(error!, style: const TextStyle(fontSize: 16)),
          ),
        Expanded(
          child: PagedList(
            key: ValueKey(d),
            itemCount: count,
            itemHeight: 52,
            emptyText: widget.pickFolder ? 'No subfolders.' : 'No folders or audio files here.',
            itemBuilder: (context, i) {
              if (isDrives) {
                return _fileRow(context, '▣  ${drives[i]}', () => _go(drives[i]));
              }
              final e = entries[i];
              final name = p.basename(e.path);
              if (e is Directory) return _fileRow(context, '▸  $name', () => _go(e.path), bold: true);
              final inLib = store.bookAt(e.path) != null;
              return _fileRow(context, '♪  $name${inLib ? '' : '  (not in library)'}', () => _openFile(e.path));
            },
          ),
        ),
      ],
    );
  }

  Widget _fileRow(BuildContext context, String label, VoidCallback onTap, {bool bold = false}) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 4),
        decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: fgOf(context), width: 1)),
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 17, fontWeight: bold ? FontWeight.w800 : FontWeight.w500),
        ),
      ),
    );
  }
}

class FolderPickerPage extends StatelessWidget {
  const FolderPickerPage({super.key});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  EButton('Cancel', onTap: () => Navigator.pop(context)),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text('Choose library folder', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                  ),
                ],
              ),
              const Expanded(child: FileBrowser(pickFolder: true)),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------- settings ----------
class SettingsTab extends StatelessWidget {
  const SettingsTab({super.key});

  Widget _choice<T>(String title, List<(String, T)> options, T current, void Function(T) set) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(title, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
          const SizedBox(height: 6),
          Row(
            children: [
              for (var i = 0; i < options.length; i++)
                Expanded(
                  child: Padding(
                    padding: EdgeInsets.only(left: i == 0 ? 0 : 4),
                    child: EButton(
                      options[i].$1,
                      selected: options[i].$2 == current,
                      fontSize: 14,
                      onTap: () {
                        set(options[i].$2);
                        store.saveSettings();
                      },
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = store.settings;
    return ListView(
      children: [
        const SectionTitle('Library'),
        Text(s.root ?? 'No folder chosen', style: const TextStyle(fontSize: 15)),
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(child: EButton('Change folder', onTap: () => pickLibraryRoot(context))),
            const SizedBox(width: 6),
            Expanded(
              child: EButton(
                store.scanning ? 'Scanning…' : 'Rescan',
                onTap: store.scanning || s.root == null ? null : store.rescan,
              ),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 10),
          child: Text(
            '${store.books.length} books on ${store.shelves.length} shelves. Subfolders of the library folder become shelves.',
            style: const TextStyle(fontSize: 14),
          ),
        ),
        const SectionTitle('Playback'),
        _choice(
          'Skip back',
          const [('10s', 10), ('15s', 15), ('30s', 30), ('60s', 60)],
          s.skipBack,
          (v) => s.skipBack = v,
        ),
        _choice(
          'Skip forward',
          const [('10s', 10), ('15s', 15), ('30s', 30), ('60s', 60)],
          s.skipFwd,
          (v) => s.skipFwd = v,
        ),
        _choice(
          'Smart rewind after a long pause',
          const [('On', true), ('Off', false)],
          s.smartRewind,
          (v) => s.smartRewind = v,
        ),
        if (Platform.isAndroid) const _BackgroundSection(),
        const SectionTitle('Display'),
        _choice(
          'Progress refresh (lower = more e-ink flashing)',
          const [('1s', 1), ('5s', 5), ('15s', 15), ('Manual', 0)],
          s.refreshSecs,
          (v) => s.refreshSecs = v,
        ),
        _choice('Lists', const [('Pages', true), ('Scroll', false)], s.paged, (v) => s.paged = v),
        _choice('Colors', const [('Black on white', false), ('White on black', true)], s.invert, (v) => s.invert = v),
        _choice(
          'Covers',
          const [('Off', 'off'), ('Grayscale', 'gray'), ('Color', 'color')],
          s.covers,
          (v) => s.covers = v,
        ),
        _choice(
          'Text size',
          const [('S', 0.9), ('M', 1.0), ('L', 1.15), ('XL', 1.3)],
          s.textScale,
          (v) => s.textScale = v,
        ),
        if (Platform.isWindows)
          const Padding(
            padding: EdgeInsets.only(top: 4, bottom: 16),
            child: Text(
              'Keys: Space play/pause · ←/→ skip · [ / ] chapter · −/= speed · B bookmark',
              style: TextStyle(fontSize: 14),
            ),
          ),
        const SizedBox(height: 20),
      ],
    );
  }
}

// ---------- mini player ----------
class MiniPlayer extends StatelessWidget {
  const MiniPlayer({super.key});
  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: playback,
      builder: (context, _) {
        final b = playback.book;
        if (b == null) return const SizedBox.shrink();
        return Container(
          decoration: BoxDecoration(
            border: Border(top: BorderSide(color: fgOf(context), width: 3)),
          ),
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
          child: Row(
            children: [
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => pushPage(context, const PlayerScreen()),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        b.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
                      ),
                      Ticking(
                        builder: (context) {
                          final pos = playback.position;
                          final i = playback.chapterIndexAt(pos);
                          final left = playback.chapterEnd(i) - pos;
                          return Text(
                            '${playback.chapters[i].title} · ${fmtDur(left)} left',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 14),
                          );
                        },
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 6),
              EButton('−${store.settings.skipBack}', onTap: playback.skipBack),
              const SizedBox(width: 6),
              SizedBox(
                width: 72,
                child: EButton(
                  playback.playing ? 'Pause' : 'Play',
                  icon: playback.playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                  fontSize: 34,
                  selected: true,
                  onTap: playback.togglePlay,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

// ---------- background playback help (Android) ----------
class _BackgroundSection extends StatefulWidget {
  const _BackgroundSection();
  @override
  State<_BackgroundSection> createState() => _BackgroundSectionState();
}

class _BackgroundSectionState extends State<_BackgroundSection> {
  bool? exempt;
  bool boox = false;
  Map<String, dynamic>? svc;
  late final AppLifecycleListener _life;

  @override
  void initState() {
    super.initState();
    _refresh();
    _life = AppLifecycleListener(onResume: _refresh);
  }

  @override
  void dispose() {
    _life.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final e = await storageChannel.invokeMethod<bool>('isBatteryExempt');
    final b = await storageChannel.invokeMethod<bool>('isBoox');
    final sv = await storageChannel.invokeMapMethod<String, dynamic>('serviceState');
    if (!mounted) return;
    setState(() {
      exempt = e;
      boox = b ?? false;
      svc = sv;
    });
  }

  @override
  Widget build(BuildContext context) {
    const bold = TextStyle(fontSize: 15, fontWeight: FontWeight.w700);
    final sv = svc;
    String service;
    if (sv == null) {
      service = 'Playback service: checking…';
    } else if (!playback.playing) {
      service = 'Playback service: start a book playing, then tap Check';
    } else if (sv['foreground'] == true) {
      service = 'Playback service: running in foreground ✓';
    } else if (sv['running'] == true) {
      service = 'Playback service: running but NOT in foreground ✗';
    } else {
      service = 'Playback service: NOT running ✗';
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SectionTitle('Background playback'),
        Text(
          exempt == true ? 'Battery optimization: off for Monotone ✓' : 'Battery optimization: ON (can stop playback)',
          style: bold,
        ),
        if (sv != null)
          Text(
            sv['notifications'] == true ? 'Notifications: allowed ✓' : 'Notifications: BLOCKED (allow in App settings)',
            style: bold,
          ),
        Text(service, style: bold),
        if (sv != null) Text('${sv['device']}', style: const TextStyle(fontSize: 13)),
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(child: EButton('Check', fontSize: 14, onTap: _refresh)),
            const SizedBox(width: 6),
            Expanded(
              child: EButton(
                'Battery exemption',
                fontSize: 14,
                onTap: exempt == true ? null : () => storageChannel.invokeMethod('requestBatteryExempt'),
              ),
            ),
            const SizedBox(width: 6),
            Expanded(
              child: EButton('App settings', fontSize: 14, onTap: () => storageChannel.invokeMethod('openAppSettings')),
            ),
          ],
        ),
        if (boox)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text(
              'Boox can pause background apps on its own. In Monotone\'s app settings, allow background '
              'activity / unrestricted battery, and turn off any freeze or cleanup option your firmware shows.',
              style: TextStyle(fontSize: 14),
            ),
          ),
        const SizedBox(height: 14),
      ],
    );
  }
}
