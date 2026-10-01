import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:just_audio/just_audio.dart';

import 'library.dart';
import 'mp4.dart';

class Playback extends ChangeNotifier {
  /// Set by the Android media handler to publish the notification's metadata.
  void Function(Book)? onBookOpened;
  final Store store;
  final AudioPlayer player = AudioPlayer();
  Book? book;
  String? error;

  // Sleep timer
  DateTime? sleepAt;
  Duration? sleepChapterEnd;
  Timer? _tick;
  Timer? _saveTimer;
  DateTime? _pausedAt;
  bool _fading = false;
  bool _wasPlaying = false;

  Playback(this.store) {
    player.playerStateStream.listen((s) {
      if (s.processingState == ProcessingState.completed && book != null) {
        final st = store.stateFor(book!.path);
        st.finished = true;
        st.position = book!.duration;
        store.saveStateNow();
        player.pause();
      }
      if (_wasPlaying && !s.playing) {
        _pausedAt = DateTime.now();
        _savePosition(now: true);
      }
      _wasPlaying = s.playing;
      notifyListeners();
    });
    player.durationStream.listen((d) {
      final b = book;
      if (b != null && d != null && b.duration == Duration.zero) {
        b.duration = d;
        store.changed();
      }
    });
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => _onTick());
    _saveTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      if (player.playing) _savePosition();
    });
  }

  bool get playing => player.playing;
  Duration get position => player.position;
  Duration get duration {
    final b = book;
    if (b == null) return Duration.zero;
    return b.duration > Duration.zero ? b.duration : (player.duration ?? Duration.zero);
  }

  double get speed => player.speed;
  BookState? get state => book == null ? null : store.stateFor(book!.path);

  // ---------- chapters ----------
  List<Chapter> get chapters {
    final b = book;
    if (b == null) return const [];
    if (b.chapters.isNotEmpty) return b.chapters;
    return [Chapter(b.title, Duration.zero)];
  }

  int chapterIndexAt(Duration pos) {
    final ch = chapters;
    var i = 0;
    for (var k = 0; k < ch.length; k++) {
      if (ch[k].start <= pos + const Duration(milliseconds: 250)) i = k;
    }
    return i;
  }

  int get chapterIndex => chapterIndexAt(position);
  Duration chapterStart(int i) => chapters[i].start;
  Duration chapterEnd(int i) => i + 1 < chapters.length ? chapters[i + 1].start : duration;

  // ---------- loading ----------
  Future<void> open(Book b, {bool play = true}) async {
    if (book?.path == b.path) {
      if (play && !playing) await togglePlay();
      return;
    }
    await _savePosition(now: true);
    error = null;
    book = b;
    sleepChapterEnd = null;
    final st = store.stateFor(b.path);
    if (st.finished) {
      st.finished = false;
      st.position = Duration.zero;
    }
    if (st.speed == 1.0 && store.settings.defaultSpeed != 1.0 && !st.started) {
      st.speed = store.settings.defaultSpeed;
    }
    st.lastPlayed = DateTime.now();
    notifyListeners();
    try {
      final src = AudioSource.file(b.path);
      await player.setAudioSource(src, initialPosition: st.position);
      await player.setSpeed(st.speed);
      onBookOpened?.call(b);
      _pausedAt = null;
      store.saveStateNow();
      store.changed();
      if (play) player.play();
    } catch (e) {
      error = 'Could not open file: $e';
    }
    notifyListeners();
  }

  Future<void> restoreLast() async {
    BookState? best;
    Book? bestBook;
    for (final b in store.books) {
      final st = store.states[b.path];
      if (st?.lastPlayed == null || st!.finished) continue;
      if (best == null || st.lastPlayed!.isAfter(best.lastPlayed!)) {
        best = st;
        bestBook = b;
      }
    }
    if (bestBook != null && File(bestBook.path).existsSync()) {
      await open(bestBook, play: false);
    }
  }

  // ---------- transport ----------
  Future<void> togglePlay() async {
    if (book == null) return;
    if (playing) {
      final f = player.pause(); // flips `playing` synchronously
      notifyListeners();
      await f;
      return;
    }
    final paused = _pausedAt;
    if (store.settings.smartRewind && paused != null) {
      final away = DateTime.now().difference(paused);
      Duration back = Duration.zero;
      if (away > const Duration(hours: 1)) {
        back = const Duration(seconds: 30);
      } else if (away > const Duration(minutes: 10)) {
        back = const Duration(seconds: 15);
      } else if (away > const Duration(minutes: 1)) {
        back = const Duration(seconds: 5);
      }
      if (back > Duration.zero) await seek(position - back);
    }
    _pausedAt = null;
    player.play(); // flips `playing` synchronously; completes only when paused
    notifyListeners();
  }

  Future<void> seek(Duration to) async {
    if (book == null) return;
    final d = duration;
    if (to < Duration.zero) to = Duration.zero;
    if (d > Duration.zero && to > d) to = d - const Duration(milliseconds: 500);
    await player.seek(to);
    _savePosition();
    notifyListeners();
  }

  Future<void> skipBack() => seek(position - Duration(seconds: store.settings.skipBack));
  Future<void> skipForward() => seek(position + Duration(seconds: store.settings.skipFwd));

  Future<void> prevChapter() async {
    final i = chapterIndex;
    if (position - chapterStart(i) > const Duration(seconds: 3) || i == 0) {
      await seek(chapterStart(i));
    } else {
      await seek(chapterStart(i - 1));
    }
  }

  Future<void> nextChapter() async {
    final i = chapterIndex;
    if (i + 1 < chapters.length) await seek(chapterStart(i + 1));
  }

  Future<void> setSpeed(double s) async {
    s = (s * 100).round() / 100;
    s = s.clamp(0.5, 3.5);
    await player.setSpeed(s);
    final st = state;
    if (st != null) {
      st.speed = s;
      store.saveStateSoon();
    }
    notifyListeners();
  }

  // ---------- sleep ----------
  void setSleep(Duration? d) {
    sleepChapterEnd = null;
    sleepAt = d == null ? null : DateTime.now().add(d);
    _restoreVolume();
    notifyListeners();
  }

  void setSleepEndOfChapter() {
    sleepAt = null;
    sleepChapterEnd = chapterEnd(chapterIndex);
    _restoreVolume();
    notifyListeners();
  }

  Duration? get sleepRemaining {
    if (sleepAt != null) {
      final r = sleepAt!.difference(DateTime.now());
      return r.isNegative ? Duration.zero : r;
    }
    if (sleepChapterEnd != null) {
      final r = sleepChapterEnd! - position;
      return r.isNegative ? Duration.zero : Duration(microseconds: (r.inMicroseconds / speed).round());
    }
    return null;
  }

  void _restoreVolume() {
    if (_fading) {
      _fading = false;
      player.setVolume(1);
    }
  }

  void _onTick() {
    if (!playing) return;
    if (sleepAt != null) {
      final r = sleepAt!.difference(DateTime.now());
      if (r <= Duration.zero) {
        player.pause();
        sleepAt = null;
        _restoreVolume();
        notifyListeners();
      } else if (r < const Duration(seconds: 10)) {
        _fading = true;
        player.setVolume(r.inMilliseconds / 10000);
      }
    }
    if (sleepChapterEnd != null && position >= sleepChapterEnd! - const Duration(milliseconds: 800)) {
      player.pause();
      sleepChapterEnd = null;
      notifyListeners();
    }
  }

  // ---------- bookmarks & state ----------
  void addBookmark(String note) {
    final st = state;
    if (st == null) return;
    st.bookmarks.add(Bookmark(position, note, DateTime.now()));
    st.bookmarks.sort((a, b) => a.pos.compareTo(b.pos));
    store.saveStateNow();
    notifyListeners();
  }

  void removeBookmark(Bookmark bm) {
    state?.bookmarks.remove(bm);
    store.saveStateNow();
    notifyListeners();
  }

  void setFinished(Book b, bool finished) {
    final st = store.stateFor(b.path);
    st.finished = finished;
    if (!finished) st.position = Duration.zero;
    if (book?.path == b.path && !finished) seek(Duration.zero);
    if (book?.path == b.path && finished) player.pause();
    store.saveStateNow();
    store.changed();
    notifyListeners();
  }

  Future<void> _savePosition({bool now = false}) async {
    final b = book;
    if (b == null || player.audioSource == null) return;
    final st = store.stateFor(b.path);
    if (st.finished) return;
    st.position = player.position;
    if (now) {
      store.saveStateNow();
    } else {
      store.saveStateSoon();
    }
  }

  Future<void> saveNow() => _savePosition(now: true);

  @override
  void dispose() {
    _tick?.cancel();
    _saveTimer?.cancel();
    player.dispose();
    super.dispose();
  }
}
