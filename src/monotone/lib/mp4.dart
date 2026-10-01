// Minimal MP4/M4B reader: tags, cover art, duration and chapters.
// Supports both Nero chapters (udta/chpl) and QuickTime text-track chapters,
// which covers what ffmpeg, m4b-tool, Audible rips and most taggers write.
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

class Chapter {
  final String title;
  final Duration start;
  const Chapter(this.title, this.start);

  Map<String, dynamic> toJson() => {'t': title, 's': start.inMilliseconds};
  factory Chapter.fromJson(Map<String, dynamic> j) => Chapter(j['t'] as String, Duration(milliseconds: j['s'] as int));
}

class AudioMeta {
  String? title, artist, albumArtist, album, narrator, description, year;
  Duration? duration;
  List<Chapter> chapters = [];
  Uint8List? cover;
}

class _Box {
  final String type;
  final int start; // content start (after header)
  final int end;
  const _Box(this.type, this.start, this.end);
}

class _Buf {
  final Uint8List bytes;
  final ByteData data;
  _Buf(this.bytes) : data = ByteData.sublistView(bytes);

  String type(int p) => String.fromCharCodes(bytes, p, p + 4);

  Iterable<_Box> children(int start, int end) sync* {
    var p = start;
    while (p + 8 <= end) {
      var size = data.getUint32(p);
      var header = 8;
      if (size == 1) {
        if (p + 16 > end) return;
        size = data.getUint64(p + 8);
        header = 16;
      } else if (size == 0) {
        size = end - p;
      }
      if (size < header || p + size > end) return;
      yield _Box(type(p + 4), p + header, p + size);
      p += size;
    }
  }

  _Box? child(_Box parent, String t) {
    for (final b in children(parent.start, parent.end)) {
      if (b.type == t) return b;
    }
    return null;
  }

  _Box? path(_Box root, List<String> types) {
    _Box? cur = root;
    for (final t in types) {
      if (cur == null) return null;
      cur = t == 'meta' ? _metaAsChildHolder(cur) : child(cur, t);
    }
    return cur;
  }

  // 'meta' is usually a full box (4 bytes version/flags) but QuickTime-style
  // files omit that. Detect by where the 'hdlr' child sits.
  _Box? _metaAsChildHolder(_Box parent) {
    final m = child(parent, 'meta');
    if (m == null) return null;
    if (m.start + 8 <= m.end && type(m.start + 4) == 'hdlr') return m;
    return _Box('meta', m.start + 4, m.end);
  }

  String str(int start, int end) => utf8.decode(bytes.sublist(start, end), allowMalformed: true).trim();
}

const _maxMoov = 256 * 1024 * 1024;

AudioMeta readMp4(String path) {
  final meta = AudioMeta();
  final raf = File(path).openSync();
  try {
    final len = raf.lengthSync();
    var pos = 0;
    Uint8List? moov;
    while (pos + 8 <= len) {
      raf.setPositionSync(pos);
      final h = raf.readSync(16);
      if (h.length < 8) break;
      final hd = ByteData.sublistView(h);
      var size = hd.getUint32(0);
      final t = String.fromCharCodes(h, 4, 8);
      var header = 8;
      if (size == 1 && h.length >= 16) {
        size = hd.getUint64(8);
        header = 16;
      } else if (size == 0) {
        size = len - pos;
      }
      if (size < header) break;
      if (t == 'moov') {
        final body = size - header;
        if (body > _maxMoov) break;
        raf.setPositionSync(pos + header);
        moov = raf.readSync(body);
        break;
      }
      pos += size;
    }
    if (moov == null) return meta;

    final b = _Buf(moov);
    final root = _Box('moov', 0, moov.length);

    // Duration from mvhd
    final mvhd = b.child(root, 'mvhd');
    if (mvhd != null) {
      final v = moov[mvhd.start];
      final ts = b.data.getUint32(mvhd.start + (v == 1 ? 20 : 12));
      final dur = v == 1 ? b.data.getUint64(mvhd.start + 24) : b.data.getUint32(mvhd.start + 16);
      if (ts > 0) {
        meta.duration = Duration(microseconds: (dur * 1000000 / ts).round());
      }
    }

    // Tags
    final ilst = b.path(root, ['udta', 'meta', 'ilst']);
    if (ilst != null) {
      for (final item in b.children(ilst.start, ilst.end)) {
        final data = b.child(item, 'data');
        if (data == null || data.end - data.start < 8) continue;
        final ps = data.start + 8;
        if (item.type == 'covr') {
          meta.cover ??= Uint8List.fromList(moov.sublist(ps, data.end));
          continue;
        }
        final s = b.str(ps, data.end);
        if (s.isEmpty) continue;
        switch (item.type) {
          case '\u00a9nam':
            meta.title = s;
          case '\u00a9ART':
            meta.artist = s;
          case 'aART':
            meta.albumArtist = s;
          case '\u00a9alb':
            meta.album = s;
          case '\u00a9nrt':
            meta.narrator = s;
          case '\u00a9wrt':
            meta.narrator ??= s;
          case 'ldes':
            meta.description = s;
          case 'desc':
          case '\u00a9cmt':
            meta.description ??= s;
          case '\u00a9day':
            meta.year = s;
        }
      }
    }

    // Chapters
    final nero = _neroChapters(b, root);
    final qt = _qtChapters(b, root, raf);
    meta.chapters = qt.length > nero.length ? qt : nero;
  } finally {
    raf.closeSync();
  }
  return meta;
}

List<Chapter> _neroChapters(_Buf b, _Box root) {
  final chpl = b.path(root, ['udta', 'chpl']);
  if (chpl == null) return [];
  try {
    var p = chpl.start;
    final version = b.bytes[p];
    p += 4;
    if (version == 1) p += 4;
    final n = b.bytes[p++];
    final out = <Chapter>[];
    for (var i = 0; i < n && p + 9 <= chpl.end; i++) {
      final start = b.data.getUint64(p); // 100ns units
      p += 8;
      final l = b.bytes[p++];
      if (p + l > chpl.end) break;
      final title = b.str(p, p + l);
      p += l;
      out.add(Chapter(title.isEmpty ? 'Chapter ${i + 1}' : title, Duration(microseconds: start ~/ 10)));
    }
    return out;
  } catch (_) {
    return [];
  }
}

List<Chapter> _qtChapters(_Buf b, _Box root, RandomAccessFile raf) {
  try {
    for (final trak in b.children(root.start, root.end)) {
      if (trak.type != 'trak') continue;
      final hdlr = b.path(trak, ['mdia', 'hdlr']);
      if (hdlr == null || hdlr.start + 12 > hdlr.end) continue;
      if (b.type(hdlr.start + 8) != 'text') continue;

      final mdhd = b.path(trak, ['mdia', 'mdhd']);
      final stbl = b.path(trak, ['mdia', 'minf', 'stbl']);
      if (mdhd == null || stbl == null) continue;
      final v = b.bytes[mdhd.start];
      final ts = b.data.getUint32(mdhd.start + (v == 1 ? 20 : 12));
      if (ts == 0) continue;

      final stts = b.child(stbl, 'stts');
      final stsz = b.child(stbl, 'stsz');
      final stsc = b.child(stbl, 'stsc');
      final stco = b.child(stbl, 'stco') ?? b.child(stbl, 'co64');
      if (stts == null || stsz == null || stsc == null || stco == null) continue;

      // Sample start times
      final times = <int>[];
      var t = 0;
      final nStts = b.data.getUint32(stts.start + 4);
      for (var i = 0; i < nStts; i++) {
        final o = stts.start + 8 + i * 8;
        final count = b.data.getUint32(o);
        final delta = b.data.getUint32(o + 4);
        for (var k = 0; k < count; k++) {
          times.add(t);
          t += delta;
        }
      }

      // Sample sizes
      final fixed = b.data.getUint32(stsz.start + 4);
      final nSamples = b.data.getUint32(stsz.start + 8);
      final sizes = List<int>.generate(nSamples, (i) => fixed != 0 ? fixed : b.data.getUint32(stsz.start + 12 + i * 4));

      // Chunk offsets
      final is64 = stco.type == 'co64';
      final nChunks = b.data.getUint32(stco.start + 4);
      final chunkOffsets = List<int>.generate(
        nChunks,
        (i) => is64 ? b.data.getUint64(stco.start + 8 + i * 8) : b.data.getUint32(stco.start + 8 + i * 4),
      );

      // Sample-to-chunk
      final nStsc = b.data.getUint32(stsc.start + 4);
      final firstChunk = <int>[], perChunk = <int>[];
      for (var i = 0; i < nStsc; i++) {
        final o = stsc.start + 8 + i * 12;
        firstChunk.add(b.data.getUint32(o));
        perChunk.add(b.data.getUint32(o + 4));
      }

      final offsets = <int>[];
      var entry = 0;
      for (var c = 1; c <= nChunks && offsets.length < nSamples; c++) {
        while (entry + 1 < nStsc && firstChunk[entry + 1] <= c) {
          entry++;
        }
        var off = chunkOffsets[c - 1];
        for (var k = 0; k < perChunk[entry] && offsets.length < nSamples; k++) {
          offsets.add(off);
          off += sizes[offsets.length - 1];
        }
      }

      final out = <Chapter>[];
      for (var i = 0; i < offsets.length && i < times.length; i++) {
        String title = '';
        if (sizes[i] >= 2) {
          raf.setPositionSync(offsets[i]);
          final s = raf.readSync(sizes[i]);
          if (s.length >= 2) {
            final l = (s[0] << 8) | s[1];
            if (2 + l <= s.length) {
              title = utf8.decode(s.sublist(2, 2 + l), allowMalformed: true).trim();
            }
          }
        }
        out.add(
          Chapter(
            title.isEmpty ? 'Chapter ${i + 1}' : title,
            Duration(microseconds: (times[i] * 1000000 / ts).round()),
          ),
        );
      }
      if (out.isNotEmpty) return out;
    }
  } catch (_) {}
  return [];
}
