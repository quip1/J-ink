import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import 'mp4.dart';

const audioExts = {'.m4b', '.m4a', '.mp3', '.aac', '.ogg', '.opus', '.flac', '.wav'};
const unshelved = 'Unsorted';

bool isAudio(String path) => audioExts.contains(p.extension(path).toLowerCase());

class Book {
  final String path;
  String title, author, narrator, description, shelf;
  Duration duration;
  List<Chapter> chapters;
  String? coverPath;
  int size, mtime;

  Book({
    required this.path,
    required this.title,
    this.author = '',
    this.narrator = '',
    this.description = '',
    this.shelf = unshelved,
    this.duration = Duration.zero,
    this.chapters = const [],
    this.coverPath,
    this.size = 0,
    this.mtime = 0,
  });

  Map<String, dynamic> toJson() => {
    'path': path,
    'title': title,
    'author': author,
    'narrator': narrator,
    'desc': description,
    'shelf': shelf,
    'dur': duration.inMilliseconds,
    'ch': chapters.map((c) => c.toJson()).toList(),
    'cover': coverPath,
    'size': size,
    'mtime': mtime,
  };

  factory Book.fromJson(Map<String, dynamic> j) => Book(
    path: j['path'],
    title: j['title'],
    author: j['author'] ?? '',
    narrator: j['narrator'] ?? '',
    description: j['desc'] ?? '',
    shelf: j['shelf'] ?? unshelved,
    duration: Duration(milliseconds: j['dur'] ?? 0),
    chapters: [for (final c in (j['ch'] as List? ?? [])) Chapter.fromJson(Map<String, dynamic>.from(c))],
    coverPath: j['cover'],
    size: j['size'] ?? 0,
    mtime: j['mtime'] ?? 0,
  );
}

class Bookmark {
  final Duration pos;
  final String note;
  final DateTime created;
  Bookmark(this.pos, this.note, this.created);
  Map<String, dynamic> toJson() => {'p': pos.inMilliseconds, 'n': note, 'c': created.millisecondsSinceEpoch};
  factory Bookmark.fromJson(Map<String, dynamic> j) =>
      Bookmark(Duration(milliseconds: j['p']), j['n'] ?? '', DateTime.fromMillisecondsSinceEpoch(j['c'] ?? 0));
}

class BookState {
  Duration position = Duration.zero;
  bool finished = false;
  double speed = 1.0;
  DateTime? lastPlayed;
  List<Bookmark> bookmarks = [];

  BookState();
  bool get started => position > Duration.zero || lastPlayed != null;

  Map<String, dynamic> toJson() => {
    'pos': position.inMilliseconds,
    'fin': finished,
    'spd': speed,
    'last': lastPlayed?.millisecondsSinceEpoch,
    'bm': bookmarks.map((b) => b.toJson()).toList(),
  };
  factory BookState.fromJson(Map<String, dynamic> j) => BookState()
    ..position = Duration(milliseconds: j['pos'] ?? 0)
    ..finished = j['fin'] ?? false
    ..speed = (j['spd'] ?? 1.0).toDouble()
    ..lastPlayed = j['last'] == null ? null : DateTime.fromMillisecondsSinceEpoch(j['last'])
    ..bookmarks = [for (final b in (j['bm'] as List? ?? [])) Bookmark.fromJson(Map<String, dynamic>.from(b))];
}

class Settings {
  String? root;
  int skipBack = 30;
  int skipFwd = 30;
  bool smartRewind = true;
  int refreshSecs = Platform.isAndroid ? 5 : 1; // 0 = manual
  bool paged = true;
  bool invert = false;
  String covers = 'gray'; // off | gray | color
  double textScale = 1.0;
  String sort = 'title'; // title | author | recent | progress
  double defaultSpeed = 1.0;
  bool askedBattery = false;

  Map<String, dynamic> toJson() => {
    'root': root,
    'skipBack': skipBack,
    'skipFwd': skipFwd,
    'smartRewind': smartRewind,
    'refreshSecs': refreshSecs,
    'paged': paged,
    'invert': invert,
    'covers': covers,
    'textScale': textScale,
    'sort': sort,
    'defaultSpeed': defaultSpeed,
    'askedBattery': askedBattery,
  };

  void load(Map<String, dynamic> j) {
    root = j['root'];
    skipBack = j['skipBack'] ?? skipBack;
    skipFwd = j['skipFwd'] ?? skipFwd;
    smartRewind = j['smartRewind'] ?? smartRewind;
    refreshSecs = j['refreshSecs'] ?? refreshSecs;
    paged = j['paged'] ?? paged;
    invert = j['invert'] ?? invert;
    covers = j['covers'] ?? covers;
    textScale = (j['textScale'] ?? textScale).toDouble();
    sort = j['sort'] ?? sort;
    defaultSpeed = (j['defaultSpeed'] ?? defaultSpeed).toDouble();
    askedBattery = j['askedBattery'] ?? askedBattery;
  }
}

class Store extends ChangeNotifier {
  late final Directory dataDir;
  late final Directory coverDir;
  final settings = Settings();
  List<Book> books = [];
  final Map<String, BookState> states = {};
  bool scanning = false;
  String? scanError;
  Timer? _saveTimer;

  static Future<Store> load() async {
    final s = Store();
    s.dataDir = await getApplicationSupportDirectory();
    s.coverDir = Directory(p.join(s.dataDir.path, 'covers'))..createSync(recursive: true);
    final j = s._readJson('settings.json');
    if (j is Map) s.settings.load(Map<String, dynamic>.from(j));
    final lib = s._readJson('library.json');
    if (lib is List) {
      s.books = [for (final b in lib) Book.fromJson(Map<String, dynamic>.from(b))];
    }
    final st = s._readJson('state.json');
    if (st is Map) {
      st.forEach((k, v) => s.states[k] = BookState.fromJson(Map<String, dynamic>.from(v)));
    }
    return s;
  }

  dynamic _readJson(String name) {
    try {
      final f = File(p.join(dataDir.path, name));
      if (!f.existsSync()) return null;
      return jsonDecode(f.readAsStringSync());
    } catch (_) {
      return null;
    }
  }

  void _writeJson(String name, Object data) {
    final f = File(p.join(dataDir.path, name));
    final tmp = File('${f.path}.tmp');
    tmp.writeAsStringSync(jsonEncode(data));
    tmp.renameSync(f.path);
  }

  void saveSettings() {
    _writeJson('settings.json', settings.toJson());
    notifyListeners();
  }

  void saveStateNow() {
    _saveTimer?.cancel();
    _writeJson('state.json', states.map((k, v) => MapEntry(k, v.toJson())));
  }

  void saveStateSoon() {
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(seconds: 2), saveStateNow);
  }

  void changed() => notifyListeners();

  BookState stateFor(String path) => states.putIfAbsent(path, BookState.new);

  Book? bookAt(String path) {
    for (final b in books) {
      if (b.path == path) return b;
    }
    return null;
  }

  double progress(Book b) {
    final st = states[b.path];
    if (st == null) return 0;
    if (st.finished) return 1;
    if (b.duration.inMilliseconds == 0) return 0;
    return (st.position.inMilliseconds / b.duration.inMilliseconds).clamp(0, 1);
  }

  List<String> get shelves {
    final s = books.map((b) => b.shelf).toSet().toList()
      ..sort((a, b) {
        if (a == unshelved) return 1;
        if (b == unshelved) return -1;
        return a.toLowerCase().compareTo(b.toLowerCase());
      });
    return s;
  }

  List<Book> sorted(Iterable<Book> input, [String? by]) {
    final list = input.toList();
    switch (by ?? settings.sort) {
      case 'author':
        list.sort((a, b) {
          final c = a.author.toLowerCase().compareTo(b.author.toLowerCase());
          return c != 0 ? c : _titleKey(a).compareTo(_titleKey(b));
        });
      case 'recent':
        list.sort((a, b) {
          final la = states[a.path]?.lastPlayed?.millisecondsSinceEpoch ?? 0;
          final lb = states[b.path]?.lastPlayed?.millisecondsSinceEpoch ?? 0;
          return lb != la ? lb.compareTo(la) : _titleKey(a).compareTo(_titleKey(b));
        });
      case 'progress':
        list.sort((a, b) => progress(b).compareTo(progress(a)));
      default:
        list.sort((a, b) => _titleKey(a).compareTo(_titleKey(b)));
    }
    return list;
  }

  static String _titleKey(Book b) {
    final t = b.title.toLowerCase();
    for (final art in ['the ', 'a ', 'an ']) {
      if (t.startsWith(art)) return t.substring(art.length);
    }
    return t;
  }

  List<Book> get inProgress {
    final list = books.where((b) {
      final st = states[b.path];
      return st != null && !st.finished && st.started;
    }).toList();
    return sorted(list, 'recent');
  }

  Future<void> rescan() async {
    final root = settings.root;
    if (root == null || scanning) return;
    scanning = true;
    scanError = null;
    notifyListeners();
    try {
      final prev = {for (final b in books) b.path: b.toJson()};
      final coverPath = coverDir.path;
      final result = await Isolate.run(() => scanLibrary(root, coverPath, prev));
      books = [for (final j in result) Book.fromJson(j)];
      _writeJson('library.json', books.map((b) => b.toJson()).toList());
    } catch (e) {
      scanError = '$e';
    }
    scanning = false;
    notifyListeners();
  }
}

// ---------------- scanning (runs in an isolate) ----------------

String _hash(String s) {
  var h = 0xcbf29ce484222325;
  for (final c in utf8.encode(s)) {
    h ^= c;
    h = (h * 0x100000001b3) & 0x7fffffffffffffff;
  }
  return h.toRadixString(16);
}

void _walk(Directory dir, List<File> out, int depth) {
  if (depth > 12) return;
  List<FileSystemEntity> entries;
  try {
    entries = dir.listSync(followLinks: false);
  } catch (_) {
    return;
  }
  for (final e in entries) {
    final name = p.basename(e.path);
    if (name.startsWith('.')) continue;
    if (e is Directory) {
      _walk(e, out, depth + 1);
    } else if (e is File && isAudio(e.path)) {
      out.add(e);
    }
  }
}

String? _folderCover(File f) {
  final dir = f.parent.path;
  final base = p.basenameWithoutExtension(f.path);
  for (final name in [
    '$base.jpg',
    '$base.png',
    'cover.jpg',
    'cover.png',
    'folder.jpg',
    'folder.png',
    'Cover.jpg',
    'Folder.jpg',
  ]) {
    final c = File(p.join(dir, name));
    if (c.existsSync()) return c.path;
  }
  return null;
}

Map<String, dynamic> readBookFile(File f, String rootPath, String coverDir) {
  final stat = f.statSync();
  final rel = p.relative(f.path, from: rootPath);
  final parts = p.split(rel);
  final shelf = parts.length > 1 ? parts.first : unshelved;
  final ext = p.extension(f.path).toLowerCase();

  AudioMeta meta = AudioMeta();
  if (ext == '.m4b' || ext == '.m4a' || ext == '.aac') {
    try {
      meta = readMp4(f.path);
    } catch (_) {}
  }

  String? cover;
  if (meta.cover != null && meta.cover!.length > 100) {
    final isPng = meta.cover![0] == 0x89;
    final cf = File(p.join(coverDir, '${_hash(f.path)}.${isPng ? 'png' : 'jpg'}'));
    try {
      cf.writeAsBytesSync(meta.cover!);
      cover = cf.path;
    } catch (_) {}
  }
  cover ??= _folderCover(f);

  return Book(
    path: f.path,
    title: meta.title ?? meta.album ?? p.basenameWithoutExtension(f.path),
    author: meta.artist ?? meta.albumArtist ?? '',
    narrator: meta.narrator ?? '',
    description: meta.description ?? '',
    shelf: shelf,
    duration: meta.duration ?? Duration.zero,
    chapters: meta.chapters,
    coverPath: cover,
    size: stat.size,
    mtime: stat.modified.millisecondsSinceEpoch,
  ).toJson();
}

List<Map<String, dynamic>> scanLibrary(String root, String coverDir, Map<String, Map<String, dynamic>> prev) {
  final dir = Directory(root);
  if (!dir.existsSync()) throw 'Folder not found: $root';
  final files = <File>[];
  _walk(dir, files, 0);
  final out = <Map<String, dynamic>>[];
  for (final f in files) {
    try {
      final stat = f.statSync();
      final old = prev[f.path];
      final coverOk = old?['cover'] == null || File(old!['cover']).existsSync();
      if (old != null && old['size'] == stat.size && old['mtime'] == stat.modified.millisecondsSinceEpoch && coverOk) {
        // unchanged: keep cached metadata, but shelf may change if root changed
        final rel = p.split(p.relative(f.path, from: root));
        out.add({...old, 'shelf': rel.length > 1 ? rel.first : unshelved});
      } else {
        out.add(readBookFile(f, root, coverDir));
      }
    } catch (_) {}
  }
  return out;
}

/// Read a single file that isn't in the library (opened from the file browser).
Future<Book> loadLooseBook(String path, String coverDir) async {
  final j = await Isolate.run(() => readBookFile(File(path), p.dirname(path), coverDir));
  return Book.fromJson(j)..shelf = unshelved;
}
