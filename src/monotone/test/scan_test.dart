import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:monotone/library.dart';

void main() {
  test('scan', () {
    Directory('/tmp/covers').createSync(recursive: true);
    final r = scanLibrary('/tmp/lib', '/tmp/covers', {});
    for (final b in r) {
      // ignore: avoid_print
      print('${b['shelf']} | ${b['title']} | ${b['author']} | ch=${(b['ch'] as List).length} | cover=${b['cover']}');
    }
    expect(r.length, 3);
    final shelves = r.map((b) => b['shelf']).toSet();
    expect(shelves, {'Fantasy', 'SciFi', unshelved});
    final t0 = DateTime.now();
    final r2 = scanLibrary('/tmp/lib', '/tmp/covers', {for (final b in r) b['path'] as String: b});
    expect(r2.length, 3);
    // ignore: avoid_print
    print('cached rescan ${DateTime.now().difference(t0).inMilliseconds}ms');
  });
}
