import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:monotone/mp4.dart';

// Uses sample files generated with ffmpeg in /tmp/lib (skipped if absent).
void main() {
  const wok = '/tmp/lib/Fantasy/Sanderson/wok.m4b';
  const big = '/tmp/lib/SciFi/big.m4b';

  test('tags, cover, Nero/QT chapters', () {
    final m = readMp4(wok);
    expect(m.title, 'The Way of Kings');
    expect(m.artist, 'Brandon Sanderson');
    expect(m.narrator, 'Michael Kramer');
    expect(m.cover, isNotNull);
    expect(m.chapters.length, 3);
    expect(m.chapters[2].title, 'Épilogue — Ünïcode');
    expect(m.chapters[1].start, const Duration(seconds: 20));
    expect(m.duration!.inSeconds, 60);
  }, skip: !File(wok).existsSync());

  test('more than 255 chapters (QuickTime text track)', () {
    final m = readMp4(big);
    expect(m.chapters.length, 300);
    expect(m.chapters.last.title, 'Part 300');
    expect(m.chapters.last.start, const Duration(seconds: 299));
  }, skip: !File(big).existsSync());
}
